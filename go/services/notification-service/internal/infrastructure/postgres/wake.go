package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.Wake = (*Listener)(nil)

// Listener implements port.Wake with Postgres LISTEN/NOTIFY (02-pipeline.md
// §5.3): the consumer NOTIFYs delivery_ready_<lane> after commit and the
// deliver workers of that lane skip their idle back-off.
//
// One dedicated connection (hijacked from the pool so the pool never hands it
// to a query) is owned by a background goroutine that fans notifications out
// to a per-lane channel of capacity 1. Sends never block: a burst of NOTIFYs
// collapses into one wake-up, which is all a poller needs. On any connection
// error the goroutine reconnects with back-off; while disconnected Wait
// simply falls back to its timeout, so a broken LISTEN degrades to plain
// polling instead of stalling a lane.
type Listener struct {
	pool   *pgxpool.Pool
	lanes  []entity.Lane
	logger *zap.Logger
	wakes  map[entity.Lane]chan struct{}
	done   chan struct{}
}

const (
	listenBackoffMin = 500 * time.Millisecond
	listenBackoffMax = 30 * time.Second
	// listenPoll bounds one WaitForNotification so ctx cancellation and
	// dead connections are noticed even when nothing is ever notified.
	listenPoll = 30 * time.Second
)

// NewListener starts listening for the given lanes until ctx is cancelled.
func NewListener(ctx context.Context, pool *pgxpool.Pool, lanes []entity.Lane, logger *zap.Logger) *Listener {
	if logger == nil {
		logger = zap.NewNop()
	}
	l := &Listener{
		pool:   pool,
		lanes:  append([]entity.Lane(nil), lanes...),
		logger: logger.Named("pg-listener"),
		wakes:  make(map[entity.Lane]chan struct{}, len(lanes)),
		done:   make(chan struct{}),
	}
	for _, lane := range lanes {
		l.wakes[lane] = make(chan struct{}, 1)
	}
	go l.run(ctx)
	return l
}

// Wait blocks until a notification for lane arrives, timeout elapses or ctx
// is cancelled; it returns true only when woken by a notification. A lane
// this listener was not started for just waits out the timeout.
func (l *Listener) Wait(ctx context.Context, lane entity.Lane, timeout time.Duration) bool {
	ch, ok := l.wakes[lane]
	if !ok {
		ch = nil // nil channel never fires
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ch:
		return true
	case <-timer.C:
		return false
	case <-ctx.Done():
		return false
	}
}

// Done is closed once the background goroutine has released its connection.
func (l *Listener) Done() <-chan struct{} { return l.done }

func (l *Listener) run(ctx context.Context) {
	defer close(l.done)
	backoff := listenBackoffMin
	for ctx.Err() == nil {
		err := l.listenOnce(ctx)
		if ctx.Err() != nil {
			return
		}
		l.logger.Warn("listen connection lost; reconnecting", zap.Error(err), zap.Duration("backoff", backoff))
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
			return
		}
		backoff *= 2
		if backoff > listenBackoffMax {
			backoff = listenBackoffMax
		}
	}
}

// listenOnce holds one connection until it fails or ctx ends.
func (l *Listener) listenOnce(ctx context.Context) error {
	poolConn, err := l.pool.Acquire(ctx)
	if err != nil {
		return err
	}
	// Hijack: the connection leaves the pool for good; LISTEN state is
	// per-session and must never leak into an ordinary query.
	conn := poolConn.Hijack()
	defer func() {
		closeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
		defer cancel()
		_ = conn.Close(closeCtx)
	}()

	for _, lane := range l.lanes {
		if _, err := conn.Exec(ctx, "LISTEN "+pgx.Identifier{wakeChannel(lane)}.Sanitize()); err != nil {
			return err
		}
	}
	l.logger.Info("listening", zap.Int("lanes", len(l.lanes)))

	for {
		waitCtx, cancel := context.WithTimeout(ctx, listenPoll)
		n, err := conn.WaitForNotification(waitCtx)
		cancel()
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) && ctx.Err() == nil {
				// Quiet period; make sure the session is still alive.
				if pingErr := conn.Ping(ctx); pingErr != nil {
					return pingErr
				}
				continue
			}
			return err
		}
		l.dispatch(n.Channel)
	}
}

func (l *Listener) dispatch(channel string) {
	for lane, ch := range l.wakes {
		if wakeChannel(lane) != channel {
			continue
		}
		select {
		case ch <- struct{}{}:
		default: // a wake-up is already pending; coalesce
		}
		return
	}
}

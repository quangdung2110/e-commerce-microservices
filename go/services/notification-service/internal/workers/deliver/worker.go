// Package deliverworker runs the deliver worker pool of one lane
// (02-pipeline.md §5.3): N goroutines per channel that claim ready rows with
// FOR UPDATE SKIP LOCKED, process the batch concurrently while a heartbeat
// extends the lease (06-backpressure.md §6.2), and back off adaptively when
// the queue is empty. Per-row logic lives in
// internal/domain/service/deliver.
package deliverworker

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Processor is what the pool calls for each claimed row (deliver.Service).
type Processor interface {
	Process(ctx context.Context, d entity.Delivery, workerID string) error
}

// Config sizes the pool.
type Config struct {
	Lane    config.LaneConfig
	Deliver config.DeliverConfig
	// InstanceID identifies this process in locked_by
	// (<lane>-<channel>-<instance>-<n>).
	InstanceID string
	// OtherLanesInProcess tells critical workers whether a lower lane runs in
	// this process and can probe a half-open provider breaker (06 §5).
	OtherLanesInProcess bool
}

// Deps are the pool's collaborators.
type Deps struct {
	Store     port.Store
	Processor Processor
	// Breaker is the provider breaker (half-open probing rule).
	Breaker port.CircuitBreaker
	// Wake is optional: LISTEN/NOTIFY for the critical lane.
	Wake    port.Wake
	Clock   clock.Clock
	Metrics *telemetry.Metrics // nilable
	Logger  *zap.Logger        // nilable
}

// Pool runs the workers of one lane.
type Pool struct {
	lane entity.Lane
	cfg  Config
	deps Deps
}

// NewPool builds the pool for a lane; call Run to start it.
func NewPool(lane entity.Lane, cfg Config, deps Deps) *Pool {
	if deps.Logger == nil {
		deps.Logger = zap.NewNop()
	}
	if deps.Clock == nil {
		deps.Clock = clock.System{}
	}
	if cfg.InstanceID == "" {
		cfg.InstanceID = "local"
	}
	if cfg.Lane.BatchSize <= 0 {
		cfg.Lane.BatchSize = 10
	}
	if cfg.Deliver.Lease <= 0 {
		cfg.Deliver.Lease = 60 * time.Second
	}
	if cfg.Deliver.HeartbeatInterval <= 0 {
		cfg.Deliver.HeartbeatInterval = cfg.Deliver.Lease / 3
	}
	if cfg.Deliver.IdleMinSleep <= 0 {
		cfg.Deliver.IdleMinSleep = 50 * time.Millisecond
	}
	if cfg.Deliver.IdleMaxSleep <= 0 {
		cfg.Deliver.IdleMaxSleep = time.Second
	}
	deps.Logger = deps.Logger.Named("deliver").With(zap.String("lane", string(lane)))
	return &Pool{lane: lane, cfg: cfg, deps: deps}
}

// Run starts every worker goroutine and blocks until ctx is cancelled and
// all in-flight batches have finished.
func (p *Pool) Run(ctx context.Context) {
	var wg sync.WaitGroup
	start := func(ch entity.Channel, n int) {
		if p.deps.Metrics != nil {
			p.deps.Metrics.BulkheadCapacity.WithLabelValues(string(p.lane), string(ch), "threads").Set(float64(n))
		}
		for i := 0; i < n; i++ {
			w := &worker{
				pool:    p,
				channel: ch,
				id:      fmt.Sprintf("%s-%s-%s-%d", p.lane, ch, p.cfg.InstanceID, i),
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				w.loop(ctx)
			}()
		}
	}
	start(entity.ChannelEmail, p.cfg.Lane.EmailWorkers)
	start(entity.ChannelInApp, p.cfg.Lane.InAppWorkers)
	p.deps.Logger.Info("deliver pool started",
		zap.Int("email_workers", p.cfg.Lane.EmailWorkers),
		zap.Int("in_app_workers", p.cfg.Lane.InAppWorkers),
		zap.Int("batch", p.cfg.Lane.BatchSize))
	wg.Wait()
	p.deps.Logger.Info("deliver pool stopped")
}

type worker struct {
	pool    *Pool
	channel entity.Channel
	id      string
}

func (w *worker) loop(ctx context.Context) {
	p := w.pool
	log := p.deps.Logger.With(zap.String("worker", w.id), zap.String("channel", string(w.channel)))
	sleep := p.cfg.Deliver.IdleMinSleep
	for ctx.Err() == nil {
		// Half-open probing (06 §5): do not spend an OTP on a probe when a
		// lower lane in this process can do it.
		if w.channel == entity.ChannelEmail && p.lane == entity.LaneCritical && p.cfg.OtherLanesInProcess &&
			p.deps.Breaker != nil && p.deps.Breaker.State() == breaker.StateHalfOpen {
			w.wait(ctx, p.cfg.Deliver.IdleMaxSleep)
			continue
		}

		claimStart := p.deps.Clock.Now()
		rows, err := p.deps.Store.Deliveries().Claim(ctx, p.lane, w.channel, w.id, p.cfg.Lane.BatchSize, p.cfg.Deliver.Lease)
		if p.deps.Metrics != nil {
			p.deps.Metrics.DeliveryClaimDuration.WithLabelValues(string(p.lane)).Observe(p.deps.Clock.Now().Sub(claimStart).Seconds())
		}
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Error("claim failed", zap.Error(err))
			w.wait(ctx, p.cfg.Deliver.IdleMaxSleep)
			continue
		}
		if len(rows) == 0 {
			w.wait(ctx, sleep)
			sleep = nextSleep(sleep, p.cfg.Deliver.IdleMaxSleep)
			continue
		}
		sleep = p.cfg.Deliver.IdleMinSleep
		w.processBatch(ctx, rows, log)
	}
}

// nextSleep doubles the idle sleep up to max (02 §5.3: 50 ms → 1 s).
func nextSleep(cur, max time.Duration) time.Duration {
	next := cur * 2
	if next > max {
		return max
	}
	return next
}

// wait sleeps for d or until woken (critical lane with LISTEN/NOTIFY) or
// ctx is cancelled.
func (w *worker) wait(ctx context.Context, d time.Duration) {
	p := w.pool
	if p.deps.Wake != nil && p.lane == entity.LaneCritical {
		p.deps.Wake.Wait(ctx, p.lane, d)
		return
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

// processBatch handles every claimed row concurrently and keeps their leases
// alive until each finishes. Rows in flight are completed even when ctx is
// cancelled (graceful stop); a hard ceiling of three leases keeps shutdown
// bounded while the heartbeat still matters for slow batches.
func (w *worker) processBatch(ctx context.Context, rows []entity.Delivery, log *zap.Logger) {
	p := w.pool
	batchCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 3*p.cfg.Deliver.Lease)
	defer cancel()

	inflight := newInflight(rows)
	hbDone := make(chan struct{})
	go w.heartbeat(batchCtx, inflight, hbDone)

	if p.deps.Metrics != nil {
		g := p.deps.Metrics.BulkheadInUse.WithLabelValues(string(p.lane), string(w.channel), "threads")
		g.Inc()
		defer g.Dec()
	}

	var wg sync.WaitGroup
	for _, row := range rows {
		wg.Add(1)
		go func(d entity.Delivery) {
			defer wg.Done()
			defer inflight.done(d.ID)
			defer func() {
				if rec := recover(); rec != nil {
					log.Error("panic while processing delivery; row will be reaped", zap.String("delivery_id", d.ID.String()), zap.Any("panic", rec))
				}
			}()
			if err := p.deps.Processor.Process(batchCtx, d, w.id); err != nil {
				log.Error("delivery processing failed; row stays SENDING until the reaper", zap.String("delivery_id", d.ID.String()), zap.Error(err))
			}
		}(row)
	}
	wg.Wait()
	cancel()
	<-hbDone
}

// heartbeat extends the lease of rows still in flight every
// heartbeat_interval (06 §6.2 option 3).
func (w *worker) heartbeat(ctx context.Context, inflight *inflightSet, done chan<- struct{}) {
	defer close(done)
	p := w.pool
	t := time.NewTicker(p.cfg.Deliver.HeartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			ids := inflight.ids()
			if len(ids) == 0 {
				return
			}
			if err := p.deps.Store.Deliveries().ExtendLease(ctx, ids, w.id, p.cfg.Deliver.Lease); err != nil {
				p.deps.Logger.Warn("lease heartbeat failed", zap.String("worker", w.id), zap.Int("rows", len(ids)), zap.Error(err))
			}
		}
	}
}

// inflightSet tracks which claimed rows are still being processed.
type inflightSet struct {
	mu  sync.Mutex
	set map[uuid.UUID]struct{}
}

func newInflight(rows []entity.Delivery) *inflightSet {
	s := &inflightSet{set: make(map[uuid.UUID]struct{}, len(rows))}
	for _, r := range rows {
		s.set[r.ID] = struct{}{}
	}
	return s
}

func (s *inflightSet) done(id uuid.UUID) {
	s.mu.Lock()
	delete(s.set, id)
	s.mu.Unlock()
}

func (s *inflightSet) ids() []uuid.UUID {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]uuid.UUID, 0, len(s.set))
	for id := range s.set {
		out = append(out, id)
	}
	return out
}

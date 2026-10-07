package deliver

import (
	"context"
	stderrors "errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// Order ordering (05-ordering.md §4 cách 3, D-05). A delivery with an
// ordering_key (only /services/order rows) may reach the provider only while
// it owns the per-subject lease in notification_ordering_lease, so two
// notifications of one order never start a provider call at the same time;
// under the lease the row is re-checked against the highest sequence and a
// lower one is SKIPPED (superseded). The lease orders the *start* of provider
// calls while ownership holds — it cannot make SES deliver in order, and a
// timeout after the provider accepted leaves the outcome uncertain.

// Attempt outcomes of the ordering lease (delivery_attempts_total).
const (
	OutcomeOrderingBusy = "ordering_busy"
	OutcomeOrderingLost = "ordering_lease_lost"
)

// Stages of ordering_lease_lost_total.
const (
	stageBeforeSend = "before_send"
	stageDuringSend = "during_send"
)

// errOrderingLost aborts the in_app transaction when the lease is gone.
var errOrderingLost = stderrors.New("deliver: ordering lease lost")

// orderingGuard owns the lease of one claimed row while it is processed.
type orderingGuard struct {
	svc    *Service
	r      *run
	key    string
	source string

	mu         sync.Mutex
	validUntil time.Time
	revoked    bool

	cancel context.CancelFunc
	done   chan struct{}
}

// acquireOrdering takes the lease and starts its heartbeat. handled=true means
// the row was already released (lease busy) and Process must return.
func (s *Service) acquireOrdering(r *run) (g *orderingGuard, handled bool, err error) {
	d := r.d
	repo := s.deps.Store.Ordering()
	lease := s.cfg.Deliver.OrderingLease
	start := s.deps.Clock.Now()
	ok, err := repo.AcquireLease(r.ctx, *d.OrderingKey, d.ID, r.workerID, lease)
	if err != nil {
		return nil, false, fmt.Errorf("deliver: acquire ordering lease: %w", err)
	}
	if !ok {
		// Another delivery of the same order is in flight: wait for it
		// without spending an attempt (06-backpressure.md §5 release).
		if s.deps.Metrics != nil {
			s.deps.Metrics.OrderingLeaseContention.WithLabelValues(d.Source).Inc()
		}
		return nil, true, s.release(r, r.now.Add(s.cfg.Deliver.OrderingBusyRetry), entity.ReasonOrderingBusy, OutcomeOrderingBusy)
	}
	ctx, cancel := context.WithCancel(context.WithoutCancel(r.ctx))
	g = &orderingGuard{svc: s, r: r, key: *d.OrderingKey, source: d.Source,
		validUntil: start.Add(lease), cancel: cancel, done: make(chan struct{})}
	go g.heartbeat(ctx)
	return g, false, nil
}

// heartbeat extends the lease every ordering_heartbeat_interval. A refused
// extension (another owner, or already expired) revokes the guard for good;
// a database error leaves validUntil to run out on its own.
func (g *orderingGuard) heartbeat(ctx context.Context) {
	defer close(g.done)
	s, d := g.svc, g.r.d
	t := time.NewTicker(s.cfg.Deliver.OrderingHeartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			at := s.deps.Clock.Now()
			ok, err := s.deps.Store.Ordering().ExtendLease(ctx, g.key, d.ID, g.r.workerID, s.cfg.Deliver.OrderingLease)
			if err != nil {
				if ctx.Err() == nil {
					g.r.log.Warn("ordering lease heartbeat failed", zap.Error(err))
				}
				continue
			}
			g.mu.Lock()
			if !ok {
				g.revoked = true
				g.mu.Unlock()
				g.r.log.Warn("ordering lease lost: heartbeat refused")
				return
			}
			g.validUntil = at.Add(s.cfg.Deliver.OrderingLease)
			g.mu.Unlock()
		}
	}
}

// lostWithin reports whether ownership is gone or would not survive the next
// `budget` (the provider call): never start a call the lease cannot cover.
func (g *orderingGuard) lostWithin(budget time.Duration) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.revoked || !g.svc.deps.Clock.Now().Add(budget).Before(g.validUntil)
}

// stop ends the heartbeat and deletes the lease if this row still owns it
// (compare-and-delete on key + delivery + worker).
func (g *orderingGuard) stop() {
	g.cancel()
	<-g.done
	ctx, cancel := context.WithTimeout(context.WithoutCancel(g.r.ctx), 5*time.Second)
	defer cancel()
	if _, err := g.svc.deps.Store.Ordering().ReleaseLease(ctx, g.key, g.r.d.ID, g.r.workerID); err != nil {
		g.r.log.Warn("ordering lease release failed; the reaper will reclaim it", zap.Error(err))
	}
}

// lost records ordering_lease_lost_total{stage}.
func (g *orderingGuard) lost(stage string) {
	if m := g.svc.deps.Metrics; m != nil {
		m.OrderingLeaseLost.WithLabelValues(g.source, stage).Inc()
	}
	g.r.log.Warn("ordering lease lost", zap.String("stage", stage))
}

// supersededUnderLease re-reads the highest sequence of the subject now that
// no other delivery of the order can start: a lower sequence must not reach
// the provider after a newer notification was ingested. Every key version is
// read (dual-read) when the raw subject is still on the row.
func (s *Service) supersededUnderLease(r *run) (bool, error) {
	d := r.d
	if d.SourceSequence == nil {
		return false, nil
	}
	source, parsed, ok := pii.ParseOrderingKey(*d.OrderingKey)
	if !ok {
		return false, nil
	}
	keys := []pii.KeyedHash{parsed}
	if d.Subject != nil && s.deps.Keyring != nil {
		keys = s.deps.Keyring.SubjectHashes(source, *d.Subject)
	}
	rows, err := s.deps.Store.Ordering().SubjectSequences(r.ctx, source, keys)
	if err != nil {
		return false, fmt.Errorf("deliver: read subject sequence: %w", err)
	}
	for _, row := range rows {
		if *d.SourceSequence < row.HighestSequence {
			return true, nil
		}
	}
	return false, nil
}

// sendBudget is the longest a provider call may take; the ordering lease must
// still be valid for that long before the call starts.
func (s *Service) sendBudget() time.Duration {
	return s.cfg.Email.ConnectTimeout + s.cfg.Email.SendTimeout
}

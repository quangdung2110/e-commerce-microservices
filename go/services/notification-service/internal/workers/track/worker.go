// Package track runs the worker that drains provider_event
// (03-tracking.md §3 steps 4–7, same claim/poll pattern as the deliver
// worker but in the opposite direction).
package track

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	tracksvc "github.com/ecommerce/notification-service/internal/domain/service/track"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// errorBackoff is the pause after a failed batch (rolled back) so a broken
// database is not hammered.
const errorBackoff = time.Second

// maxRetryMultiplier caps the linear unmatched backoff (retry × min(attempt, 10)).
const maxRetryMultiplier = 10

// Worker claims ready provider events in batches and applies them through
// the track service inside one transaction per batch.
type Worker struct {
	cfg     config.TrackConfig
	store   port.Store
	svc     *tracksvc.Service
	clock   clock.Clock
	metrics *telemetry.Metrics
	log     *zap.Logger
}

// New wires the worker.
func New(cfg config.TrackConfig, store port.Store, svc *tracksvc.Service, clk clock.Clock, m *telemetry.Metrics, log *zap.Logger) *Worker {
	if clk == nil {
		clk = clock.System{}
	}
	if log == nil {
		log = zap.NewNop()
	}
	return &Worker{cfg: cfg, store: store, svc: svc, clock: clk, metrics: m, log: log.With(zap.String("component", "track_worker"))}
}

func (w *Worker) batch() int {
	if w.cfg.WorkerBatch <= 0 {
		return 50
	}
	return w.cfg.WorkerBatch
}

func (w *Worker) poll() time.Duration {
	if w.cfg.WorkerPoll <= 0 {
		return time.Second
	}
	return w.cfg.WorkerPoll
}

// Run loops until ctx is cancelled: process a batch; when it was full go
// again immediately, otherwise wait one poll interval. Returns ctx.Err().
func (w *Worker) Run(ctx context.Context) error {
	w.log.Info("track worker started", zap.Int("batch", w.batch()), zap.Duration("poll", w.poll()))
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		n, err := w.RunOnce(ctx)
		w.exportUnmatchedAge(ctx)

		wait := w.poll()
		switch {
		case err != nil:
			if ctx.Err() != nil {
				return ctx.Err()
			}
			w.log.Error("track batch failed; rolled back", zap.Error(err))
			wait = errorBackoff
		case n >= w.batch():
			wait = 0
		}
		if wait == 0 {
			continue
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(wait):
		}
	}
}

// RunOnce claims and processes one batch in one transaction. Any error rolls
// the whole batch back (the rows return to the queue untouched) and is
// returned. It reports how many rows were claimed.
func (w *Worker) RunOnce(ctx context.Context) (int, error) {
	claimed := 0
	err := w.store.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		now := w.clock.Now()
		events, err := tx.ProviderEvents().Claim(ctx, w.batch(), now)
		if err != nil {
			return err
		}
		claimed = len(events)
		for i := range events {
			if err := w.processOne(ctx, tx, &events[i]); err != nil {
				return err
			}
		}
		return nil
	})
	return claimed, err
}

func (w *Worker) processOne(ctx context.Context, tx port.Tx, ev *entity.ProviderEvent) error {
	log := w.log.With(zap.Int64("provider_event_id", ev.ID), zap.String("event_type", string(ev.EventType)), zap.Int("attempt", ev.Attempt))
	out, err := w.svc.Process(ctx, tx, ev)
	if err != nil {
		return err
	}
	now := w.clock.Now()
	repo := tx.ProviderEvents()

	if out.Result != tracksvc.ResultUnmatched {
		return repo.SetStatus(ctx, ev.ID, ev.ReceivedAt, entity.ProviderEventProcessed)
	}

	plan := PlanUnmatched(ev.Attempt, ev.ReceivedAt, now, w.cfg)
	if plan.GiveUp {
		log.Warn("provider event unmatched past give-up window; ignoring",
			zap.Duration("age", now.Sub(ev.ReceivedAt)), zap.String("provider_message_id", derefString(ev.ProviderMessageID)))
		return repo.SetStatus(ctx, ev.ID, ev.ReceivedAt, entity.ProviderEventIgnored)
	}
	if plan.Alert {
		// 03-tracking.md §5.1: after N misses it is a mail sent outside the
		// system or a correlation bug; the unmatched-age gauge alerts.
		log.Warn("provider event still unmatched",
			zap.Int("attempt", plan.Attempt), zap.String("provider_message_id", derefString(ev.ProviderMessageID)), zap.Time("next_attempt_at", plan.NextAttemptAt))
	} else {
		log.Debug("provider event unmatched; rescheduled", zap.Time("next_attempt_at", plan.NextAttemptAt))
	}
	// Status first so a repository whose Reschedule leaves status alone still
	// ends in UNMATCHED; a Reschedule that also sets it is harmless.
	if err := repo.SetStatus(ctx, ev.ID, ev.ReceivedAt, entity.ProviderEventUnmatched); err != nil {
		return err
	}
	return repo.Reschedule(ctx, ev.ID, ev.ReceivedAt, plan.Attempt, plan.NextAttemptAt)
}

// exportUnmatchedAge refreshes provider_event_unmatched_age_seconds each cycle
// (14-observability.md §2.4; > 5 min is a correlation bug).
func (w *Worker) exportUnmatchedAge(ctx context.Context) {
	if w.metrics == nil || ctx.Err() != nil {
		return
	}
	age, err := w.store.ProviderEvents().OldestUnmatchedAge(ctx, w.clock.Now())
	if err != nil {
		w.log.Debug("oldest unmatched age query failed", zap.Error(err))
		return
	}
	w.metrics.ProviderEventUnmatchedAge.Set(age.Seconds())
}

// Plan is the reschedule decision for an unmatched event.
type Plan struct {
	// GiveUp is true when the event is older than unmatched_give_up: it is
	// marked IGNORED instead of rescheduled.
	GiveUp bool
	// Attempt is the incremented attempt counter to store.
	Attempt int
	// NextAttemptAt = now + unmatched_retry × min(attempt, 10).
	NextAttemptAt time.Time
	// Alert is true from unmatched_alert_after attempts on.
	Alert bool
}

// PlanUnmatched is the pure reschedule arithmetic (tested without a store).
// prevAttempt is the attempt counter as stored before this try.
func PlanUnmatched(prevAttempt int, receivedAt, now time.Time, cfg config.TrackConfig) Plan {
	attempt := prevAttempt + 1
	retry := cfg.UnmatchedRetry
	if retry <= 0 {
		retry = 30 * time.Second
	}
	giveUp := cfg.UnmatchedGiveUp
	if giveUp <= 0 {
		giveUp = 24 * time.Hour
	}
	alertAfter := cfg.UnmatchedAlertAfter
	if alertAfter <= 0 {
		alertAfter = 3
	}
	mult := attempt
	if mult > maxRetryMultiplier {
		mult = maxRetryMultiplier
	}
	return Plan{
		GiveUp:        now.Sub(receivedAt) >= giveUp,
		Attempt:       attempt,
		NextAttemptAt: now.Add(retry * time.Duration(mult)),
		Alert:         attempt >= alertAfter,
	}
}

func derefString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

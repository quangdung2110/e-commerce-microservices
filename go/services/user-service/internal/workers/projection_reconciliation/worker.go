// Package projectionreconciliation republishes the current profile and
// preference snapshot of every active user once a day, which is how the design
// converges without an outbox (WF-USER-008, ADR-007).
package projectionreconciliation

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/config"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// Worker performs the projection reconciliation sweep.
type Worker struct {
	reconciliation *service.ReconciliationService
	cfg            config.ReconciliationConfig
}

// New wires the reconciliation worker.
func New(reconciliation *service.ReconciliationService, cfg config.ReconciliationConfig) *Worker {
	return &Worker{reconciliation: reconciliation, cfg: cfg}
}

// Name identifies the worker and its lock. The lock is what keeps the sweep
// single-active across replicas, so a full scan never runs twice at once.
func (w *Worker) Name() string { return "user-projection-reconciliation" }

// Interval is how often the worker runs; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one sweep.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// InitialDelay waits for the next configured off-peak hour instead of starting
// a full scan the moment the process comes up.
func (w *Worker) InitialDelay(now time.Time) time.Duration {
	now = now.UTC()
	next := time.Date(now.Year(), now.Month(), now.Day(), w.cfg.RunAtHourUTC, 0, 0, 0, time.UTC)
	if !next.After(now) {
		next = next.Add(24 * time.Hour)
	}
	return next.Sub(now)
}

// Run publishes one corrective snapshot per active user. `failed` counts the
// users whose snapshot could not be published; it is what the reconciliation
// alert watches, because those projections stay stale until the next sweep.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	result, err := w.reconciliation.Reconcile(ctx, w.cfg.Batch, w.cfg.BatchGap)

	return []zap.Field{
		zap.Int("scanned", result.Scanned),
		zap.Int("published", result.Published),
		zap.Int("failed", result.Failed),
	}, err
}

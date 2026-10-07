// Package retentionpurge hard-deletes anonymized profiles once the deadline
// Auth published has passed (WF-USER-007, ADR-005).
package retentionpurge

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/config"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// Worker performs the compliance hard delete.
type Worker struct {
	lifecycle *service.LifecycleService
	cfg       config.WorkerConfig
}

// New wires the retention purge worker.
func New(lifecycle *service.LifecycleService, cfg config.WorkerConfig) *Worker {
	return &Worker{lifecycle: lifecycle, cfg: cfg}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "user-retention-purge" }

// Interval is how often the worker runs; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run purges one batch. `oldest_due` is the field the compliance backlog alert
// watches: it grows when purging falls behind.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	result, err := w.lifecycle.PurgeDue(ctx, w.cfg.Batch)

	fields := []zap.Field{
		zap.Int("due", result.Due),
		zap.Int("purged", result.Purged),
		zap.Int("skipped", result.Skipped),
		zap.Int("failed", result.Failed),
	}
	if !result.OldestDue.IsZero() {
		fields = append(fields, zap.Time("oldest_due", result.OldestDue))
	}
	return fields, err
}

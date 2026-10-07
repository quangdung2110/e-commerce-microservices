// Package refreshtokencleanup removes refresh-token rows that have been revoked
// or expired for longer than the retention window.
package refreshtokencleanup

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Worker deletes stale session rows in bounded batches.
type Worker struct {
	maintenance *service.MaintenanceService
	cfg         config.WorkerConfig
	retention   time.Duration
}

// New wires the cleanup worker.
func New(maintenance *service.MaintenanceService, cfg config.WorkerConfig, retention time.Duration) *Worker {
	return &Worker{maintenance: maintenance, cfg: cfg, retention: retention}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "refresh-token-cleanup" }

// Interval is how often the cleanup runs; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run deletes one batch. The batch is idempotent, so a partial pass simply
// continues on the next tick.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	deleted, err := w.maintenance.CleanupExpiredSessions(ctx, w.retention, w.cfg.Batch)
	return []zap.Field{zap.Int64("deleted", deleted)}, err
}

// Package autounlock returns accounts whose lockout window elapsed to ACTIVE.
package autounlock

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Worker unlocks expired lockouts and notifies the owners.
type Worker struct {
	maintenance *service.MaintenanceService
	cfg         config.WorkerConfig
}

// New wires the auto-unlock worker.
func New(maintenance *service.MaintenanceService, cfg config.WorkerConfig) *Worker {
	return &Worker{maintenance: maintenance, cfg: cfg}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "auto-unlock-account" }

// Interval is how often the worker runs; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run unlocks one batch. The transition is idempotent, so an account another
// path already unlocked is skipped rather than notified twice.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	unlocked, err := w.maintenance.UnlockExpired(ctx, w.cfg.Batch)
	return []zap.Field{zap.Int("unlocked", unlocked)}, err
}

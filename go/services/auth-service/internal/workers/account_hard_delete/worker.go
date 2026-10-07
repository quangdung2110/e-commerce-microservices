// Package accountharddelete removes DELETED accounts once their 90-day
// retention window has elapsed (AUTH-BR-007).
package accountharddelete

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Worker performs the compliance hard delete.
type Worker struct {
	maintenance *service.MaintenanceService
	cfg         config.WorkerConfig
}

// New wires the hard-delete worker.
func New(maintenance *service.MaintenanceService, cfg config.WorkerConfig) *Worker {
	return &Worker{maintenance: maintenance, cfg: cfg}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "account-hard-delete" }

// Interval is how often the worker runs; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run deletes one batch. Roles and remaining sessions cascade with the row.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	deleted, err := w.maintenance.HardDeleteDue(ctx, w.cfg.Batch)
	return []zap.Field{zap.Int64("deleted", deleted)}, err
}

// Package keyrotation replaces the ACTIVE signing key once it reaches the
// configured age (WF-AUTH-008).
package keyrotation

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Worker rotates the signing key.
type Worker struct {
	keys *service.KeyLifecycleService
	cfg  config.WorkerConfig
}

// New wires the rotation worker.
func New(keys *service.KeyLifecycleService, cfg config.WorkerConfig) *Worker {
	return &Worker{keys: keys, cfg: cfg}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "key-rotation" }

// Interval is how often rotation is considered; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run rotates the key when it is due. The old key moves to PHASE_OUT in the same
// transaction, so the service is never without exactly one ACTIVE key.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	rotated, err := w.keys.RotateIfDue(ctx)
	return []zap.Field{zap.Bool("rotated", rotated)}, err
}

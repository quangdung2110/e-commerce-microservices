// Package keyretirement retires PHASE_OUT signing keys once every access token
// they could have signed has expired (ADR-003).
package keyretirement

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Worker retires keys whose overlap window elapsed.
type Worker struct {
	keys *service.KeyLifecycleService
	cfg  config.WorkerConfig
}

// New wires the retirement worker.
func New(keys *service.KeyLifecycleService, cfg config.WorkerConfig) *Worker {
	return &Worker{keys: keys, cfg: cfg}
}

// Name identifies the worker and its lock.
func (w *Worker) Name() string { return "key-retirement" }

// Interval is how often retirement is considered; zero disables it.
func (w *Worker) Interval() time.Duration {
	if !w.cfg.Enabled {
		return 0
	}
	return w.cfg.Interval
}

// Timeout bounds one pass.
func (w *Worker) Timeout() time.Duration { return w.cfg.Timeout }

// Run retires every key that is safely past its overlap window.
func (w *Worker) Run(ctx context.Context) ([]zap.Field, error) {
	retired, err := w.keys.RetireDue(ctx)
	return []zap.Field{zap.Int("retired", retired)}, err
}

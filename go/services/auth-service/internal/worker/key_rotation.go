package worker

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/jwks"
)

// KeyRotationWorker handles JWKS key rotation
type KeyRotationWorker struct {
	rotator *jwks.Rotator
	cfg     config.WorkerTaskConfig
	logger  *zap.Logger
}

// NewKeyRotationWorker creates a new key rotation worker
func NewKeyRotationWorker(
	rotator *jwks.Rotator,
	cfg config.WorkerTaskConfig,
	logger *zap.Logger,
) *KeyRotationWorker {
	return &KeyRotationWorker{
		rotator: rotator,
		cfg:     cfg,
		logger:  logger,
	}
}

// Run executes the key rotation job
func (w *KeyRotationWorker) Run(ctx context.Context) error {
	startTime := time.Now()
	w.logger.Info("Starting key rotation job")

	// Perform rotation with distributed lock
	if err := w.rotator.Rotate(ctx); err != nil {
		w.logger.Error("Key rotation failed", zap.Error(err))
		return fmt.Errorf("key rotation failed: %w", err)
	}

	duration := time.Since(startTime)
	w.logger.Info("Key rotation job completed",
		zap.Duration("duration", duration))

	return nil
}

// ShouldRun checks if the job should run based on configuration
func (w *KeyRotationWorker) ShouldRun() bool {
	return w.cfg.Enabled
}

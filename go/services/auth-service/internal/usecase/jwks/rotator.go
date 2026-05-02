package jwks

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// Rotator handles automatic key rotation based on schedule and expiry
type Rotator struct {
	service        *Service
	lockRepo       repository.DistributedLockRepository
	cfg            config.WorkerConfig
	logger         *zap.Logger
}

// NewRotator creates a new JWKS key rotator
func NewRotator(
	service *Service,
	lockRepo repository.DistributedLockRepository,
	cfg config.WorkerConfig,
	logger *zap.Logger,
) *Rotator {
	return &Rotator{
		service:  service,
		lockRepo: lockRepo,
		cfg:      cfg,
		logger:   logger,
	}
}

// ShouldRotate checks if key rotation should be performed
func (r *Rotator) ShouldRotate(ctx context.Context) (bool, error) {
	return r.service.IsKeyRotationNeeded(ctx)
}

// Rotate performs key rotation with distributed lock
func (r *Rotator) Rotate(ctx context.Context) error {
	lockKey := "jwks:key_rotation"

	// Try to acquire distributed lock
	acquired, err := r.lockRepo.Acquire(ctx, lockKey, 5*time.Minute)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}

	if !acquired {
		r.logger.Info("Key rotation skipped: another instance is performing rotation")
		return nil
	}

	defer func() {
		if err := r.lockRepo.Release(ctx, lockKey); err != nil {
			r.logger.Warn("Failed to release key rotation lock", zap.Error(err))
		}
	}()

	// Check again if rotation is still needed (in case another instance just completed it)
	shouldRotate, err := r.service.IsKeyRotationNeeded(ctx)
	if err != nil {
		return fmt.Errorf("failed to check rotation need: %w", err)
	}

	if !shouldRotate {
		r.logger.Info("Key rotation skipped: not needed")
		return nil
	}

	// Perform rotation
	if err := r.service.RotateKey(ctx); err != nil {
		return fmt.Errorf("failed to rotate key: %w", err)
	}

	r.logger.Info("Key rotation completed successfully")
	return nil
}

// GetNextRotationTime returns the next scheduled rotation time
func (r *Rotator) GetNextRotationTime() time.Time {
	// Parse cron expression and return next run time
	// This is a simplified implementation; in production, use robfig/cron for actual scheduling
	return time.Now().Add(90 * 24 * time.Hour) // 90 days
}

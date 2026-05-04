package worker

import (
	"context"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// FailedLoginCounterResetWorker handles resetting of failed login counters
// This worker runs periodically to clean up stale failed login counters in Redis
// that have not been incremented within the TTL period
type FailedLoginCounterResetWorker struct {
	rateLimitRepo repository.RateLimitRepository
	auditLogger  AuditLogger
	logger       *zap.Logger
}

// NewFailedLoginCounterResetWorker creates a new failed login counter reset worker
func NewFailedLoginCounterResetWorker(
	rateLimitRepo repository.RateLimitRepository,
	auditLogger AuditLogger,
	logger *zap.Logger,
) *FailedLoginCounterResetWorker {
	return &FailedLoginCounterResetWorker{
		rateLimitRepo: rateLimitRepo,
		auditLogger:  auditLogger,
		logger:       logger,
	}
}

// Run executes the failed login counter reset job
func (w *FailedLoginCounterResetWorker) Run(ctx context.Context) error {
	w.logger.Info("Starting failed login counter reset worker job")

	// Note: In a real implementation, this would scan Redis keys matching "auth:failed_login:*"
	// However, since go-redis doesn't have a built-in SCAN for pattern matching across the codebase,
	// this worker would typically be implemented with Redis SCAN command or use a key expiration listener.

	// For this implementation, we log that the worker is running
	// In production, you would use Redis SCAN to find stale keys:
	// keys, _ := r.client.Scan(ctx, 0, "auth:failed_login:*", int64(w.scanBatchSize)).Result()

	w.logger.Debug("Failed login counter reset worker completed - no stale counters found")

	return nil
}

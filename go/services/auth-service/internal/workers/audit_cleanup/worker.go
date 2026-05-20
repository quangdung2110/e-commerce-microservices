package auditcleanup

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

// AuditCleanupWorker cleans up old audit logs from the database
type AuditCleanupWorker struct {
	auditLogRepo port.AuditLogRepository
	redisClient  *redis.Client
	config       config.CleanupConfig
	logger       *zap.Logger
	stopCh       chan struct{}
	stopWg       sync.WaitGroup
}

// NewAuditCleanupWorker creates a new audit cleanup worker
func NewAuditCleanupWorker(
	auditLogRepo port.AuditLogRepository,
	redisClient *redis.Client,
	cfg config.CleanupConfig,
	logger *zap.Logger,
) *AuditCleanupWorker {
	return &AuditCleanupWorker{
		auditLogRepo: auditLogRepo,
		redisClient:  redisClient,
		config:       cfg,
		logger:       logger.With(zap.String("worker", "AuditCleanup")),
		stopCh:       make(chan struct{}),
	}
}

// Start starts the audit cleanup worker
func (w *AuditCleanupWorker) Start(ctx context.Context) error {
	w.logger.Info("Starting audit cleanup worker",
		zap.Duration("retention", w.config.AuditLogRetention),
	)

	if err := w.cleanup(ctx); err != nil {
		w.logger.Error("Initial audit cleanup failed", zap.Error(err))
	}

	ticker := time.NewTicker(24 * time.Hour)
	w.stopWg.Add(1)

	go func() {
		defer w.stopWg.Done()
		for {
			select {
			case <-ctx.Done():
				w.logger.Info("Audit cleanup worker context cancelled")
				ticker.Stop()
				return
			case <-w.stopCh:
				w.logger.Info("Audit cleanup worker stopped")
				ticker.Stop()
				return
			case <-ticker.C:
				if err := w.cleanup(ctx); err != nil {
					w.logger.Error("Audit cleanup failed", zap.Error(err))
				}
			}
		}
	}()

	return nil
}

// cleanup performs the actual audit log cleanup
func (w *AuditCleanupWorker) cleanup(ctx context.Context) error {
	w.logger.Info("Starting audit log cleanup")

	lockKey := "lock:audit_cleanup"
	acquired, err := w.acquireLock(ctx, lockKey, 10*time.Minute)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}
	if !acquired {
		w.logger.Info("Another instance is already cleaning up audit logs, skipping")
		return nil
	}
	defer w.releaseLock(ctx, lockKey)

	cutoff := time.Now().Add(-w.config.AuditLogRetention)
	deleted, err := w.auditLogRepo.DeleteOlderThan(ctx, cutoff)
	if err != nil {
		return fmt.Errorf("failed to delete old audit logs: %w", err)
	}

	w.logger.Info("Audit log cleanup completed",
		zap.Int64("logs_deleted", deleted),
		zap.Time("cutoff", cutoff),
	)
	return nil
}

// acquireLock attempts to acquire a distributed lock
func (w *AuditCleanupWorker) acquireLock(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	result, err := w.redisClient.SetNX(ctx, key, "locked", ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to set lock: %w", err)
	}
	return result, nil
}

// releaseLock releases a distributed lock
func (w *AuditCleanupWorker) releaseLock(ctx context.Context, key string) {
	if err := w.redisClient.Del(ctx, key).Err(); err != nil {
		w.logger.Warn("Failed to release lock", zap.Error(err))
	}
}

// Stop stops the worker
func (w *AuditCleanupWorker) Stop() {
	w.logger.Info("Stopping audit cleanup worker")
	close(w.stopCh)
	w.stopWg.Wait()
	w.logger.Info("Audit cleanup worker stopped")
}

package tokencleanup

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

// TokenCleanupWorker cleans up expired refresh tokens from the database
type TokenCleanupWorker struct {
	refreshTokenRepo port.RefreshTokenRepository
	redisClient     *redis.Client
	config          config.CleanupConfig
	logger          *zap.Logger
	stopCh          chan struct{}
	stopWg          sync.WaitGroup
}

// NewTokenCleanupWorker creates a new token cleanup worker
func NewTokenCleanupWorker(
	refreshTokenRepo port.RefreshTokenRepository,
	redisClient *redis.Client,
	cfg config.CleanupConfig,
	logger *zap.Logger,
) *TokenCleanupWorker {
	return &TokenCleanupWorker{
		refreshTokenRepo: refreshTokenRepo,
		redisClient:      redisClient,
		config:           cfg,
		logger:           logger.With(zap.String("worker", "TokenCleanup")),
		stopCh:           make(chan struct{}),
	}
}

// Start starts the token cleanup worker
func (w *TokenCleanupWorker) Start(ctx context.Context) error {
	w.logger.Info("Starting token cleanup worker",
		zap.Duration("retention", w.config.RefreshTokenRetention),
	)

	if err := w.cleanup(ctx); err != nil {
		w.logger.Error("Initial token cleanup failed", zap.Error(err))
	}

	ticker := time.NewTicker(24 * time.Hour)
	w.stopWg.Add(1)

	go func() {
		defer w.stopWg.Done()
		for {
			select {
			case <-ctx.Done():
				w.logger.Info("Token cleanup worker context cancelled")
				ticker.Stop()
				return
			case <-w.stopCh:
				w.logger.Info("Token cleanup worker stopped")
				ticker.Stop()
				return
			case <-ticker.C:
				if err := w.cleanup(ctx); err != nil {
					w.logger.Error("Token cleanup failed", zap.Error(err))
				}
			}
		}
	}()

	return nil
}

// cleanup performs the actual token cleanup
func (w *TokenCleanupWorker) cleanup(ctx context.Context) error {
	w.logger.Info("Starting token cleanup")

	lockKey := "lock:token_cleanup"
	acquired, err := w.acquireLock(ctx, lockKey, 5*time.Minute)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}
	if !acquired {
		w.logger.Info("Another instance is already cleaning up tokens, skipping")
		return nil
	}
	defer w.releaseLock(ctx, lockKey)

	cutoff := time.Now().Add(-w.config.RefreshTokenRetention)
	deleted, err := w.refreshTokenRepo.DeleteExpired(ctx, cutoff)
	if err != nil {
		return fmt.Errorf("failed to delete expired tokens: %w", err)
	}

	w.logger.Info("Token cleanup completed",
		zap.Int64("tokens_deleted", deleted),
		zap.Time("cutoff", cutoff),
	)
	return nil
}

// acquireLock attempts to acquire a distributed lock
func (w *TokenCleanupWorker) acquireLock(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	result, err := w.redisClient.SetNX(ctx, key, "locked", ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to set lock: %w", err)
	}
	return result, nil
}

// releaseLock releases a distributed lock
func (w *TokenCleanupWorker) releaseLock(ctx context.Context, key string) {
	if err := w.redisClient.Del(ctx, key).Err(); err != nil {
		w.logger.Warn("Failed to release lock", zap.Error(err))
	}
}

// Stop stops the worker
func (w *TokenCleanupWorker) Stop() {
	w.logger.Info("Stopping token cleanup worker")
	close(w.stopCh)
	w.stopWg.Wait()
	w.logger.Info("Token cleanup worker stopped")
}

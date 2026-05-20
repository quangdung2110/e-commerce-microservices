package keyretirement

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type KeyRetirementWorker struct {
	keyService *service.KeyService
	jwksService *service.JWKSService
	redisClient *redis.Client
	config      config.KeysConfig
	logger      *zap.Logger
	stopCh      chan struct{}
	stopWg      sync.WaitGroup
}

func NewKeyRetirementWorker(
	keyService *service.KeyService,
	jwksService *service.JWKSService,
	redisClient *redis.Client,
	cfg config.KeysConfig,
	logger *zap.Logger,
) *KeyRetirementWorker {
	return &KeyRetirementWorker{
		keyService:  keyService,
		jwksService: jwksService,
		redisClient: redisClient,
		config:      cfg,
		logger:      logger.With(zap.String("worker", "KeyRetirement")),
		stopCh:      make(chan struct{}),
	}
}

// Start starts the key retirement worker
func (w *KeyRetirementWorker) Start(ctx context.Context) error {
	w.logger.Info("Starting key retirement worker",
		zap.Duration("retirement_delay", w.config.RetirementDelay),
	)

	// Run every hour
	ticker := time.NewTicker(1 * time.Hour)
	w.stopWg.Add(1)

	go func() {
		defer w.stopWg.Done()
		for {
			select {
			case <-ctx.Done():
				w.logger.Info("Key retirement worker context cancelled")
				ticker.Stop()
				return
			case <-w.stopCh:
				w.logger.Info("Key retirement worker stopped")
				ticker.Stop()
				return
			case <-ticker.C:
				if err := w.retireKeys(ctx); err != nil {
					w.logger.Error("Key retirement failed", zap.Error(err))
				}
			}
		}
	}()

	return nil
}

func (w *KeyRetirementWorker) retireKeys(ctx context.Context) error {
	w.logger.Info("Starting key retirement check")

	// Acquire distributed lock
	lockKey := "lock:key_retirement"
	acquired, err := w.acquireLock(ctx, lockKey, 5*time.Minute)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}
	if !acquired {
		w.logger.Info("Another instance is already retiring keys, skipping")
		return nil
	}
	defer w.releaseLock(ctx, lockKey)

	// Retire old keys
	if err := w.keyService.RetireOldKeys(ctx); err != nil {
		return fmt.Errorf("failed to retire keys: %w", err)
	}

	// Invalidate JWKS cache
	w.jwksService.InvalidateCache()
	w.logger.Info("JWKS cache invalidated")

	// Publish JWKS update notification
	if err := w.publishJWKSUpdate(ctx); err != nil {
		w.logger.Warn("Failed to publish JWKS update", zap.Error(err))
	}

	w.logger.Info("Key retirement check completed")
	return nil
}

func (w *KeyRetirementWorker) acquireLock(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	result, err := w.redisClient.SetNX(ctx, key, "locked", ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to set lock: %w", err)
	}
	return result, nil
}

func (w *KeyRetirementWorker) releaseLock(ctx context.Context, key string) {
	if err := w.redisClient.Del(ctx, key).Err(); err != nil {
		w.logger.Warn("Failed to release lock", zap.Error(err))
	}
}

func (w *KeyRetirementWorker) publishJWKSUpdate(ctx context.Context) error {
	channel := keybuilder.JWKSUpdateChannel()
	message := fmt.Sprintf(`{"event":"JWKS_UPDATED","timestamp":%d}`, time.Now().Unix())

	_, err := w.redisClient.Publish(ctx, channel, message).Result()
	if err != nil {
		return fmt.Errorf("failed to publish JWKS update: %w", err)
	}

	w.logger.Info("JWKS update published to Redis Pub/Sub",
		zap.String("channel", channel),
	)
	return nil
}

// Stop stops the worker gracefully
func (w *KeyRetirementWorker) Stop() {
	w.logger.Info("Stopping key retirement worker")
	close(w.stopCh)
	w.stopWg.Wait()
	w.logger.Info("Key retirement worker stopped")
}

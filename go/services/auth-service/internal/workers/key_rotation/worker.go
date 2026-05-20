package keyrotation

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

type KeyRotationWorker struct {
	keyService  *service.KeyService
	jwksService *service.JWKSService
	redisClient *redis.Client
	config      config.KeysConfig
	logger      *zap.Logger
	stopCh      chan struct{}
	stopWg      sync.WaitGroup
}

func NewKeyRotationWorker(
	keyService *service.KeyService,
	jwksService *service.JWKSService,
	redisClient *redis.Client,
	cfg config.KeysConfig,
	logger *zap.Logger,
) *KeyRotationWorker {
	return &KeyRotationWorker{
		keyService:  keyService,
		jwksService: jwksService,
		redisClient: redisClient,
		config:      cfg,
		logger:      logger.With(zap.String("worker", "KeyRotation")),
		stopCh:      make(chan struct{}),
	}
}

// Start starts the key rotation worker
func (w *KeyRotationWorker) Start(ctx context.Context) error {
	w.logger.Info("Starting key rotation worker",
		zap.Duration("rotation_interval", w.config.RotationInterval),
	)

	// Run immediately on start to ensure there's always an active key
	if err := w.keyService.EnsureActiveKey(ctx); err != nil {
		w.logger.Error("Failed to ensure active key on startup", zap.Error(err))
	}

	// Schedule periodic rotation
	ticker := time.NewTicker(w.config.RotationInterval)
	w.stopWg.Add(1)

	go func() {
		defer w.stopWg.Done()
		for {
			select {
			case <-ctx.Done():
				w.logger.Info("Key rotation worker context cancelled")
				ticker.Stop()
				return
			case <-w.stopCh:
				w.logger.Info("Key rotation worker stopped")
				ticker.Stop()
				return
			case <-ticker.C:
				if err := w.rotateKeys(ctx); err != nil {
					w.logger.Error("Key rotation failed", zap.Error(err))
				}
			}
		}
	}()

	return nil
}

func (w *KeyRotationWorker) rotateKeys(ctx context.Context) error {
	w.logger.Info("Starting key rotation")

	// Acquire distributed lock
	lockKey := "lock:key_rotation"
	acquired, err := w.acquireLock(ctx, lockKey, 30*time.Second)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}
	if !acquired {
		w.logger.Info("Another instance is already rotating keys, skipping")
		return nil
	}
	defer w.releaseLock(ctx, lockKey)

	// Create new active key
	newKey, err := w.keyService.CreateNewActiveKey(ctx)
	if err != nil {
		return fmt.Errorf("failed to create new key: %w", err)
	}
	w.logger.Info("New key created",
		zap.String("kid", newKey.Kid),
		zap.Time("created_at", newKey.CreatedAt),
	)

	// Invalidate JWKS cache
	w.jwksService.InvalidateCache()
	w.logger.Info("JWKS cache invalidated")

	// Publish JWKS update notification to Redis Pub/Sub
	if err := w.publishJWKSUpdate(ctx); err != nil {
		w.logger.Warn("Failed to publish JWKS update", zap.Error(err))
	}

	w.logger.Info("Key rotation completed successfully")
	return nil
}

func (w *KeyRotationWorker) acquireLock(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	result, err := w.redisClient.SetNX(ctx, key, "locked", ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to set lock: %w", err)
	}
	return result, nil
}

func (w *KeyRotationWorker) releaseLock(ctx context.Context, key string) {
	if err := w.redisClient.Del(ctx, key).Err(); err != nil {
		w.logger.Warn("Failed to release lock", zap.Error(err))
	}
}

func (w *KeyRotationWorker) publishJWKSUpdate(ctx context.Context) error {
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
func (w *KeyRotationWorker) Stop() {
	w.logger.Info("Stopping key rotation worker")
	close(w.stopCh)
	w.stopWg.Wait()
	w.logger.Info("Key rotation worker stopped")
}

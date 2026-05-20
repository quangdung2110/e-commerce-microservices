package autounlock

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

const (
	// DefaultLockDuration is the default duration before auto-unlocking accounts
	DefaultLockDuration = 30 * time.Minute
)

// AutoUnlockWorker automatically unlocks accounts that have been locked for the configured duration
type AutoUnlockWorker struct {
	userRepo       port.UserRepository
	eventPublisher port.EventPublisher
	redisClient   *redis.Client
	config        config.CleanupConfig
	logger        *zap.Logger
	stopCh        chan struct{}
	stopWg        sync.WaitGroup
}

// NewAutoUnlockWorker creates a new auto-unlock worker
func NewAutoUnlockWorker(
	userRepo port.UserRepository,
	eventPublisher port.EventPublisher,
	redisClient *redis.Client,
	cfg config.CleanupConfig,
	logger *zap.Logger,
) *AutoUnlockWorker {
	return &AutoUnlockWorker{
		userRepo:       userRepo,
		eventPublisher: eventPublisher,
		redisClient:    redisClient,
		config:         cfg,
		logger:         logger.With(zap.String("worker", "AutoUnlock")),
		stopCh:         make(chan struct{}),
	}
}

// Start starts the auto-unlock worker
func (w *AutoUnlockWorker) Start(ctx context.Context) error {
	w.logger.Info("Starting auto-unlock worker",
		zap.Duration("check_interval", w.config.AutoUnlockInterval),
	)

	ticker := time.NewTicker(w.config.AutoUnlockInterval)
	w.stopWg.Add(1)

	go func() {
		defer w.stopWg.Done()
		for {
			select {
			case <-ctx.Done():
				w.logger.Info("Auto-unlock worker context cancelled")
				ticker.Stop()
				return
			case <-w.stopCh:
				w.logger.Info("Auto-unlock worker stopped")
				ticker.Stop()
				return
			case <-ticker.C:
				if err := w.unlockAccounts(ctx); err != nil {
					w.logger.Error("Auto-unlock failed", zap.Error(err))
				}
			}
		}
	}()

	return nil
}

// unlockAccounts finds and unlocks accounts that have been locked past the lock duration
func (w *AutoUnlockWorker) unlockAccounts(ctx context.Context) error {
	lockKey := "lock:auto_unlock"
	acquired, err := w.acquireLock(ctx, lockKey, 1*time.Minute)
	if err != nil {
		return fmt.Errorf("failed to acquire lock: %w", err)
	}
	if !acquired {
		w.logger.Debug("Another instance is already running auto-unlock, skipping")
		return nil
	}
	defer w.releaseLock(ctx, lockKey)

	users, err := w.userRepo.GetLockedUsers(ctx, DefaultLockDuration, 100)
	if err != nil {
		return fmt.Errorf("failed to get locked users: %w", err)
	}

	if len(users) == 0 {
		return nil
	}

	w.logger.Info("Found accounts to auto-unlock", zap.Int("count", len(users)))

	for _, user := range users {
		if err := w.unlockUser(ctx, user); err != nil {
			w.logger.Error("Failed to unlock user",
				zap.String("user_id", user.ID),
				zap.Error(err),
			)
			continue
		}
	}

	return nil
}

// unlockUser unlocks a single user account and publishes the event
func (w *AutoUnlockWorker) unlockUser(ctx context.Context, user *entity.User) error {
	if err := w.userRepo.UnlockAccount(ctx, user.ID); err != nil {
		return fmt.Errorf("failed to unlock account: %w", err)
	}

	w.logger.Info("Auto-unlocked account",
		zap.String("user_id", user.ID),
		zap.String("email", user.Email.String()),
	)

	unlockEvent := event.NewUserUnlocked(
		user.ID.String(),
		user.Email.String(),
		event.UnlockTypeAuto,
		"SYSTEM",
	)

	if err := w.eventPublisher.Publish(ctx, unlockEvent); err != nil {
		w.logger.Warn("Failed to publish unlock event",
			zap.String("user_id", user.ID),
			zap.Error(err),
		)
	}

	return nil
}

// acquireLock attempts to acquire a distributed lock
func (w *AutoUnlockWorker) acquireLock(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	result, err := w.redisClient.SetNX(ctx, key, "locked", ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to set lock: %w", err)
	}
	return result, nil
}

// releaseLock releases a distributed lock
func (w *AutoUnlockWorker) releaseLock(ctx context.Context, key string) {
	if err := w.redisClient.Del(ctx, key).Err(); err != nil {
		w.logger.Warn("Failed to release lock", zap.Error(err))
	}
}

// Stop stops the worker
func (w *AutoUnlockWorker) Stop() {
	w.logger.Info("Stopping auto-unlock worker")
	close(w.stopCh)
	w.stopWg.Wait()
	w.logger.Info("Auto-unlock worker stopped")
}

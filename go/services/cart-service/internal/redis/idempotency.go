package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

// IdempotencyManager manages idempotency keys for ORDER_PAID events
// This prevents duplicate processing of order payment events
type IdempotencyManager interface {
	// CheckAndLock attempts to acquire an idempotency lock
	// Returns true if lock acquired (not processed), false if already processed
	CheckAndLock(ctx context.Context, orderID string) (bool, error)

	// MarkDone marks the order as successfully processed
	MarkDone(ctx context.Context, orderID string) error

	// IsProcessed checks if order was already processed
	IsProcessed(ctx context.Context, orderID string) (bool, error)

	// GetStatus returns the current status of an order idempotency key
	GetStatus(ctx context.Context, orderID string) (string, error)
}

// IdempotencyStatus constants
const (
	IdempotencyStatusProcessing = "PROCESSING"
	IdempotencyStatusDone      = "DONE"
)

// idempotencyManagerImpl implements IdempotencyManager
type idempotencyManagerImpl struct {
	redis      *redis.Client
	keyBuilder *keybuilder.CartKeyBuilder
	lockTTL    time.Duration
	doneTTL    time.Duration
}

// NewIdempotencyManager creates a new IdempotencyManager
func NewIdempotencyManager(
	redisClient *redis.Client,
	keyBuilder *keybuilder.CartKeyBuilder,
) IdempotencyManager {
	return &idempotencyManagerImpl{
		redis:      redisClient,
		keyBuilder: keyBuilder,
		lockTTL:    5 * time.Minute, // 300 seconds
		doneTTL:    24 * time.Hour,  // 86400 seconds
	}
}

// CheckAndLock attempts to acquire an idempotency lock using SET NX EX
// Returns true if lock acquired (not processed), false if already processed
func (m *idempotencyManagerImpl) CheckAndLock(ctx context.Context, orderID string) (bool, error) {
	key := m.keyBuilder.BuildIdempotencyKey(orderID)

	// SET NX EX - only set if not exists, with 5 minute expiry
	result, err := m.redis.SetNX(ctx, key, IdempotencyStatusProcessing, m.lockTTL).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check and lock idempotency: %w", err)
	}

	return result, nil
}

// MarkDone marks the order as successfully processed
func (m *idempotencyManagerImpl) MarkDone(ctx context.Context, orderID string) error {
	processingKey := m.keyBuilder.BuildIdempotencyKey(orderID)
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)

	// Use a pipeline to delete processing key and set done key
	pipe := m.redis.Pipeline()
	pipe.Del(ctx, processingKey)
	pipe.Set(ctx, doneKey, IdempotencyStatusDone, m.doneTTL)

	_, err := pipe.Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to mark done: %w", err)
	}

	return nil
}

// IsProcessed checks if order was already processed
func (m *idempotencyManagerImpl) IsProcessed(ctx context.Context, orderID string) (bool, error) {
	// Check done key first
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)
	exists, err := m.redis.Exists(ctx, doneKey).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check if processed: %w", err)
	}

	if exists > 0 {
		return true, nil
	}

	// Check processing key (might be stale)
	processingKey := m.keyBuilder.BuildIdempotencyKey(orderID)
	exists, err = m.redis.Exists(ctx, processingKey).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check if processing: %w", err)
	}

	// If processing key exists, it means another process is handling it
	// We should not process, but it hasn't completed successfully
	return false, nil
}

// GetStatus returns the current status of an order idempotency key
func (m *idempotencyManagerImpl) GetStatus(ctx context.Context, orderID string) (string, error) {
	// Check done key first
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)
	val, err := m.redis.Get(ctx, doneKey).Result()
	if err == nil {
		return val, nil
	}
	if err != redis.Nil {
		return "", fmt.Errorf("failed to get status: %w", err)
	}

	// Check processing key
	processingKey := m.keyBuilder.BuildIdempotencyKey(orderID)
	val, err = m.redis.Get(ctx, processingKey).Result()
	if err == nil {
		return val, nil
	}
	if err == redis.Nil {
		return "", nil
	}

	return "", fmt.Errorf("failed to get status: %w", err)
}

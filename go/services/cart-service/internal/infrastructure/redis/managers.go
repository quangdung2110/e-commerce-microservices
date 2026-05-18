package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// IntentIndexManager implements port.IntentIndexManager
type IntentIndexManager struct {
	redis      *redis.Client
	keyBuilder *CartKeyBuilder
}

// NewIntentIndexManager creates a new IntentIndexManager
func NewIntentIndexManager(redisClient *redis.Client, keyBuilder *CartKeyBuilder) *IntentIndexManager {
	return &IntentIndexManager{
		redis:      redisClient,
		keyBuilder: keyBuilder,
	}
}

// Ensure IntentIndexManager implements port.IntentIndexManager interface
var _ port.IntentIndexManager = (*IntentIndexManager)(nil)

// PreRegisterIntent registers a publish intent before Lua execution
func (m *IntentIndexManager) PreRegisterIntent(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())
	_, err := m.redis.ZAdd(ctx, port.KeyOutboxPublishIntents, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return fmt.Errorf("failed to pre-register intent: %w", err)
	}
	return nil
}

// MarkActive marks stream as active and notifies workers
func (m *IntentIndexManager) MarkActive(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())

	_, err := m.redis.ZAdd(ctx, port.KeyActiveOutboxStreams, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return fmt.Errorf("failed to mark stream as active: %w", err)
	}

	_, err = m.redis.Publish(ctx, port.KeyOutboxNotifications, streamKey).Result()
	if err != nil {
		return fmt.Errorf("failed to publish notification: %w", err)
	}

	return nil
}

// ClearIntent removes the intent after successful publish
func (m *IntentIndexManager) ClearIntent(ctx context.Context, streamKey string) error {
	_, err := m.redis.ZRem(ctx, port.KeyOutboxPublishIntents, streamKey).Result()
	if err != nil {
		return fmt.Errorf("failed to clear intent: %w", err)
	}
	return nil
}

// ExecuteWithIntent executes an operation with full intent index protocol
func (m *IntentIndexManager) ExecuteWithIntent(ctx context.Context, streamKey string, operation func() error) error {
	if err := m.PreRegisterIntent(ctx, streamKey); err != nil {
		return fmt.Errorf("failed to pre-register intent: %w", err)
	}

	if err := operation(); err != nil {
		_ = m.ClearIntent(ctx, streamKey)
		return fmt.Errorf("operation failed: %w", err)
	}

	if err := m.MarkActive(ctx, streamKey); err != nil {
		_ = m.ClearIntent(ctx, streamKey)
		return fmt.Errorf("failed to mark active: %w", err)
	}

	if err := m.ClearIntent(ctx, streamKey); err != nil {
		return fmt.Errorf("failed to clear intent: %w", err)
	}

	return nil
}

// GetStaleIntents returns intents that haven't been processed within the given duration
func (m *IntentIndexManager) GetStaleIntents(ctx context.Context, olderThan time.Duration) ([]string, error) {
	minScore := float64(time.Now().Add(-olderThan).UnixMilli())
	result, err := m.redis.ZRangeByScore(ctx, port.KeyOutboxPublishIntents, &redis.ZRangeBy{
		Min: "-inf",
		Max: fmt.Sprintf("%f", minScore),
	}).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get stale intents: %w", err)
	}
	return result, nil
}

// GetActiveOutboxStreams returns all active outbox streams from the ZSET
func (m *IntentIndexManager) GetActiveOutboxStreams(ctx context.Context) ([]string, error) {
	result, err := m.redis.ZRange(ctx, port.KeyActiveOutboxStreams, 0, -1).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get active outbox streams: %w", err)
	}
	return result, nil
}

// NegativeCacheManager implements port.NegativeCacheManager
type NegativeCacheManager struct {
	redis      *redis.Client
	keyBuilder *CartKeyBuilder
	ttl        time.Duration
}

// NewNegativeCacheManager creates a new NegativeCacheManager
func NewNegativeCacheManager(redisClient *redis.Client, keyBuilder *CartKeyBuilder, ttlSeconds int) *NegativeCacheManager {
	return &NegativeCacheManager{
		redis:      redisClient,
		keyBuilder: keyBuilder,
		ttl:        time.Duration(ttlSeconds) * time.Second,
	}
}

// HasCachedResult checks if we have a cached "not found" result
func (m *NegativeCacheManager) HasCachedResult(ctx context.Context, userID string) (bool, error) {
	key, err := m.keyBuilder.BuildNegativeCacheKey(userID)
	if err != nil {
		return false, fmt.Errorf("failed to build negative cache key: %w", err)
	}

	exists, err := m.redis.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check negative cache: %w", err)
	}

	return exists > 0, nil
}

// SetNotFound caches the "not found" result with TTL
func (m *NegativeCacheManager) SetNotFound(ctx context.Context, userID string) error {
	key, err := m.keyBuilder.BuildNegativeCacheKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build negative cache key: %w", err)
	}

	_, err = m.redis.Set(ctx, key, "1", m.ttl).Result()
	if err != nil {
		return fmt.Errorf("failed to set negative cache: %w", err)
	}

	return nil
}

// Invalidate removes the negative cache
func (m *NegativeCacheManager) Invalidate(ctx context.Context, userID string) error {
	key, err := m.keyBuilder.BuildNegativeCacheKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build negative cache key: %w", err)
	}

	_, err = m.redis.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to invalidate negative cache: %w", err)
	}

	return nil
}

// IdempotencyManager implements port.IdempotencyManager
type IdempotencyManager struct {
	redis      *redis.Client
	keyBuilder *CartKeyBuilder
	lockTTL    time.Duration
	doneTTL    time.Duration
}

// NewIdempotencyManager creates a new IdempotencyManager
func NewIdempotencyManager(redisClient *redis.Client, keyBuilder *CartKeyBuilder) *IdempotencyManager {
	return &IdempotencyManager{
		redis:      redisClient,
		keyBuilder: keyBuilder,
		lockTTL:    5 * time.Minute,
		doneTTL:    24 * time.Hour,
	}
}

// CheckAndLock attempts to acquire an idempotency lock
func (m *IdempotencyManager) CheckAndLock(ctx context.Context, orderID string) (bool, error) {
	key := m.keyBuilder.BuildIdempotencyKey(orderID)
	result, err := m.redis.SetNX(ctx, key, "PROCESSING", m.lockTTL).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check and lock idempotency: %w", err)
	}
	return result, nil
}

// MarkDone marks the order as successfully processed
func (m *IdempotencyManager) MarkDone(ctx context.Context, orderID string) error {
	processingKey := m.keyBuilder.BuildIdempotencyKey(orderID)
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)

	pipe := m.redis.Pipeline()
	pipe.Del(ctx, processingKey)
	pipe.Set(ctx, doneKey, "DONE", m.doneTTL)

	_, err := pipe.Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to mark done: %w", err)
	}

	return nil
}

// IsProcessed checks if order was already processed
func (m *IdempotencyManager) IsProcessed(ctx context.Context, orderID string) (bool, error) {
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)
	exists, err := m.redis.Exists(ctx, doneKey).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check if processed: %w", err)
	}
	if exists > 0 {
		return true, nil
	}

	processingKey := m.keyBuilder.BuildIdempotencyKey(orderID)
	exists, err = m.redis.Exists(ctx, processingKey).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check if processing: %w", err)
	}

	return false, nil
}

// GetStatus returns the current status of an order idempotency key
func (m *IdempotencyManager) GetStatus(ctx context.Context, orderID string) (string, error) {
	doneKey := m.keyBuilder.BuildIdempotencyDoneKey(orderID)
	val, err := m.redis.Get(ctx, doneKey).Result()
	if err == nil {
		return val, nil
	}
	if err != redis.Nil {
		return "", fmt.Errorf("failed to get status: %w", err)
	}

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

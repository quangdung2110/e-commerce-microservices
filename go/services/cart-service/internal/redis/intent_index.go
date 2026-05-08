package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

// IntentIndexManager manages the Intent Index protocol for Outbox Pattern
// This implements the Go-layer protocol for Redis Streams outbox:
// 1. ZADD outbox_publish_intents <now_ms> <stream_key>  (pre-register intent)
// 2. EVALSHA Lua (Hash + XADD)
// 3. ZADD active_outbox_streams <now_ms> <stream_key>   (mark as active)
// 4. PUBLISH outbox_notifications <stream_key>          (notify workers)
// 5. ZREM outbox_publish_intents <stream_key>          (clear intent on success)
type IntentIndexManager interface {
	// PreRegisterIntent registers a publish intent before Lua execution
	PreRegisterIntent(ctx context.Context, streamKey string) error

	// MarkActive marks stream as active and notifies workers
	MarkActive(ctx context.Context, streamKey string) error

	// ClearIntent removes the intent after successful publish
	ClearIntent(ctx context.Context, streamKey string) error

	// ExecuteWithIntent executes an operation with full intent index protocol
	// Returns the result of the operation
	ExecuteWithIntent(ctx context.Context, streamKey string, operation func() error) error

	// GetStaleIntents returns intents that haven't been processed
	GetStaleIntents(ctx context.Context, olderThan time.Duration) ([]string, error)

	// GetActiveStreams returns all active outbox streams
	GetActiveStreams(ctx context.Context) ([]string, error)

	// GetActiveOutboxStreams returns all active outbox streams from the ZSET
	GetActiveOutboxStreams(ctx context.Context) ([]string, error)
}

// intentIndexManagerImpl implements IntentIndexManager
type intentIndexManagerImpl struct {
	redis      *redis.Client
	keyBuilder *keybuilder.CartKeyBuilder
}

// NewIntentIndexManager creates a new IntentIndexManager
func NewIntentIndexManager(
	redisClient *redis.Client,
	keyBuilder *keybuilder.CartKeyBuilder,
) IntentIndexManager {
	return &intentIndexManagerImpl{
		redis:      redisClient,
		keyBuilder: keyBuilder,
	}
}

// PreRegisterIntent registers a publish intent before Lua execution
func (m *intentIndexManagerImpl) PreRegisterIntent(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())
	_, err := m.redis.ZAdd(ctx, keybuilder.OutboxPublishIntentsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return fmt.Errorf("failed to pre-register intent: %w", err)
	}
	return nil
}

// MarkActive marks stream as active and notifies workers
func (m *intentIndexManagerImpl) MarkActive(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())

	// Add to active outbox streams
	_, err := m.redis.ZAdd(ctx, keybuilder.ActiveOutboxStreamsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return fmt.Errorf("failed to mark stream as active: %w", err)
	}

	// Notify workers via Pub/Sub
	_, err = m.redis.Publish(ctx, keybuilder.OutboxNotificationsChannel, streamKey).Result()
	if err != nil {
		return fmt.Errorf("failed to publish notification: %w", err)
	}

	return nil
}

// ClearIntent removes the intent after successful publish
func (m *intentIndexManagerImpl) ClearIntent(ctx context.Context, streamKey string) error {
	_, err := m.redis.ZRem(ctx, keybuilder.OutboxPublishIntentsKey, streamKey).Result()
	if err != nil {
		return fmt.Errorf("failed to clear intent: %w", err)
	}
	return nil
}

// ExecuteWithIntent executes an operation with full intent index protocol
func (m *intentIndexManagerImpl) ExecuteWithIntent(
	ctx context.Context,
	streamKey string,
	operation func() error,
) error {
	// Step 1: Pre-register intent
	if err := m.PreRegisterIntent(ctx, streamKey); err != nil {
		return fmt.Errorf("failed to pre-register intent: %w", err)
	}

	// Step 2: Execute the Lua operation
	if err := operation(); err != nil {
		// Clear intent on failure
		_ = m.ClearIntent(ctx, streamKey)
		return fmt.Errorf("operation failed: %w", err)
	}

	// Step 3-4: Mark active and notify
	if err := m.MarkActive(ctx, streamKey); err != nil {
		// Still try to clear intent even if marking fails
		_ = m.ClearIntent(ctx, streamKey)
		return fmt.Errorf("failed to mark active: %w", err)
	}

	// Step 5: Clear intent on success
	if err := m.ClearIntent(ctx, streamKey); err != nil {
		// Log but don't fail - intent will be cleaned up by recovery worker
		return fmt.Errorf("failed to clear intent: %w", err)
	}

	return nil
}

// GetStaleIntents returns intents that haven't been processed within the given duration
func (m *intentIndexManagerImpl) GetStaleIntents(ctx context.Context, olderThan time.Duration) ([]string, error) {
	minScore := float64(time.Now().Add(-olderThan).UnixMilli())

	result, err := m.redis.ZRangeByScore(ctx, keybuilder.OutboxPublishIntentsKey, &redis.ZRangeBy{
		Min: "-inf",
		Max: fmt.Sprintf("%f", minScore),
	}).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get stale intents: %w", err)
	}

	return result, nil
}

// GetActiveStreams returns all active outbox streams
func (m *intentIndexManagerImpl) GetActiveStreams(ctx context.Context) ([]string, error) {
	result, err := m.redis.ZRange(ctx, keybuilder.OutboxPublishIntentsKey, 0, -1).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get active streams: %w", err)
	}

	return result, nil
}

// GetActiveOutboxStreams returns all active outbox streams from the ZSET
func (m *intentIndexManagerImpl) GetActiveOutboxStreams(ctx context.Context) ([]string, error) {
	result, err := m.redis.ZRange(ctx, keybuilder.ActiveOutboxStreamsKey, 0, -1).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get active outbox streams: %w", err)
	}

	return result, nil
}

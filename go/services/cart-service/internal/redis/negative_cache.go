package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

// NegativeCacheManager manages negative caching for cart reads
// Negative cache prevents repeated DB queries when cart doesn't exist
type NegativeCacheManager interface {
	// HasCachedResult checks if we have a cached "not found" result
	HasCachedResult(ctx context.Context, userID string) (bool, error)

	// SetNotFound caches the "not found" result with TTL
	SetNotFound(ctx context.Context, userID string) error

	// Invalidate removes the negative cache (called after successful write)
	Invalidate(ctx context.Context, userID string) error
}

// negativeCacheManagerImpl implements NegativeCacheManager
type negativeCacheManagerImpl struct {
	redis      *redis.Client
	keyBuilder *keybuilder.CartKeyBuilder
	ttl        time.Duration
}

// NewNegativeCacheManager creates a new NegativeCacheManager
func NewNegativeCacheManager(
	redisClient *redis.Client,
	keyBuilder *keybuilder.CartKeyBuilder,
	ttlSeconds int,
) NegativeCacheManager {
	return &negativeCacheManagerImpl{
		redis:      redisClient,
		keyBuilder: keyBuilder,
		ttl:        time.Duration(ttlSeconds) * time.Second,
	}
}

// HasCachedResult checks if we have a cached "not found" result
func (m *negativeCacheManagerImpl) HasCachedResult(ctx context.Context, userID string) (bool, error) {
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
func (m *negativeCacheManagerImpl) SetNotFound(ctx context.Context, userID string) error {
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

// Invalidate removes the negative cache (called after successful write)
func (m *negativeCacheManagerImpl) Invalidate(ctx context.Context, userID string) error {
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

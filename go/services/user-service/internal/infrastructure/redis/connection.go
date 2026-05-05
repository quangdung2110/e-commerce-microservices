package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"user-service/internal/domain/entity"
)

// Key constants for Redis cache
const (
	KeyPrefixAddresses = "user:caches:addresses"
	TTL12Hours        = 12 * time.Hour
	TTL30Minutes      = 30 * time.Minute
)

// AddressCache defines the interface for address caching
type AddressCache interface {
	// Get retrieves cached addresses for a user
	// Returns nil if cache miss (caller should query DB)
	Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error)

	// Set caches addresses for a user with appropriate TTL
	// Empty arrays get shorter TTL to prevent cache penetration
	Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error

	// Delete removes cached addresses for a user
	Delete(ctx context.Context, userID uuid.UUID) error
}

// RedisAddressCache implements AddressCache using Redis
type RedisAddressCache struct {
	client *redis.Client
}

// NewRedisAddressCache creates a new RedisAddressCache
func NewRedisAddressCache(client *redis.Client) *RedisAddressCache {
	return &RedisAddressCache{client: client}
}

// key generates the Redis key for user addresses
func (c *RedisAddressCache) key(userID uuid.UUID) string {
	return fmt.Sprintf("%s:%s", KeyPrefixAddresses, userID.String())
}

// Get retrieves cached addresses for a user
// Returns nil, nil on cache miss (caller should query DB)
// Returns addresses, nil on cache hit
// Returns nil, error on Redis error
func (c *RedisAddressCache) Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	key := c.key(userID)

	data, err := c.client.Get(ctx, key).Bytes()
	if err == redis.Nil {
		// Cache miss - caller should query database
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("redis get: %w", err)
	}

	var addresses []*entity.Address
	if err := json.Unmarshal(data, &addresses); err != nil {
		// Invalid cache data - treat as cache miss
		// Delete the invalid key to prevent future issues
		_ = c.client.Del(ctx, key).Err()
		return nil, nil
	}

	return addresses, nil
}

// Set caches addresses for a user with appropriate TTL
// Empty arrays get shorter TTL (30 minutes) to prevent cache penetration
// Non-empty arrays get standard TTL (12 hours)
func (c *RedisAddressCache) Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error {
	key := c.key(userID)

	data, err := json.Marshal(addresses)
	if err != nil {
		return fmt.Errorf("json marshal: %w", err)
	}

	// Apply shorter TTL for empty results to prevent cache penetration
	ttl := TTL12Hours
	if len(addresses) == 0 {
		ttl = TTL30Minutes
	}

	if err := c.client.Set(ctx, key, data, ttl).Err(); err != nil {
		return fmt.Errorf("redis set: %w", err)
	}

	return nil
}

// Delete removes cached addresses for a user
// Called after any write operation (create, update, delete) to invalidate cache
func (c *RedisAddressCache) Delete(ctx context.Context, userID uuid.UUID) error {
	key := c.key(userID)

	if err := c.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("redis del: %w", err)
	}

	return nil
}

// Ping checks Redis connectivity
func (c *RedisAddressCache) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return c.client.Ping(ctx).Err()
}

// GetTTL returns the remaining TTL for a user's address cache
// Returns 0 if key doesn't exist
func (c *RedisAddressCache) GetTTL(ctx context.Context, userID uuid.UUID) (time.Duration, error) {
	key := c.key(userID)
	return c.client.TTL(ctx, key).Result()
}

// Exists checks if cache exists for a user
func (c *RedisAddressCache) Exists(ctx context.Context, userID uuid.UUID) (bool, error) {
	key := c.key(userID)
	result, err := c.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("redis exists: %w", err)
	}
	return result > 0, nil
}

// Refresh extends the TTL for cached addresses
func (c *RedisAddressCache) Refresh(ctx context.Context, userID uuid.UUID) error {
	key := c.key(userID)
	return c.client.Expire(ctx, key, TTL12Hours).Err()
}

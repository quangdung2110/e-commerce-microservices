package redis

import (
	"context"
	"fmt"
	"time"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/port"

	"github.com/redis/go-redis/v9"
)

// CacheRepo implements port.CacheRepository for simple Redis operations.
// These are read-only or simple set operations that don't require Lua scripts.
//
// Note: Write operations (Add/Update/Delete) MUST go through CartLuaRepo
// to preserve atomicity guarantees. CacheRepo only handles:
//   - Read (GetCart, HasNegativeCache)
//   - Simple writes that don't affect cart data (negative cache, item availability)
type CacheRepo struct {
	client            *Client
	kb                *keybuilder.KeyBuilder
	negativeCacheTTL  int // seconds
}

// NewCacheRepo creates a CacheRepo with the given Redis client, key builder,
// and negative cache TTL.
func NewCacheRepo(client *Client, kb *keybuilder.KeyBuilder, negativeCacheTTLSeconds int) *CacheRepo {
	return &CacheRepo{
		client:           client,
		kb:               kb,
		negativeCacheTTL: negativeCacheTTLSeconds,
	}
}

// Compile-time interface assertion
var _ port.CacheRepository = (*CacheRepo)(nil)

// GetCart implements port.CacheRepository.
// Reads all fields from a cart hash. Returns map[skuID]JSONValue or empty map if key doesn't exist.
// Does NOT emit events. Does NOT refresh TTL (read-only operation).
func (r *CacheRepo) GetCart(ctx context.Context, cartKey string) (map[string]string, error) {
	result, err := r.client.Raw().HGetAll(ctx, cartKey).Result()
	if err != nil {
		return nil, fmt.Errorf("%w: hgetall %s: %v", port.ErrRedisConnectionFailed, cartKey, err)
	}
	// go-redis returns empty map for non-existent keys — no special nil check needed
	return result, nil
}

// DeleteNegativeCache implements port.CacheRepository.
// Removes the negative cache key for a user.
// Called after successful Add/Update/Delete to prevent stale empty responses.
func (r *CacheRepo) DeleteNegativeCache(ctx context.Context, userID string) error {
	key := r.kb.NegativeCacheKey(userID)
	err := r.client.Raw().Del(ctx, key).Err()
	if err != nil && err != redis.Nil {
		return fmt.Errorf("%w: del %s: %v", port.ErrRedisConnectionFailed, key, err)
	}
	return nil
}

// SetNegativeCache implements port.CacheRepository.
// Sets a negative cache marker (key not found indicator).
// TTL: configured negative_cache_ttl (default 60s).
// Called when cart not in Redis AND not in PostgreSQL (confirmed non-existent).
func (r *CacheRepo) SetNegativeCache(ctx context.Context, userID string) error {
	key := r.kb.NegativeCacheKey(userID)
	err := r.client.Raw().SetEx(ctx, key, "1", negativeCacheDuration(r.negativeCacheTTL)).Err()
	if err != nil {
		return fmt.Errorf("%w: setex %s: %v", port.ErrRedisConnectionFailed, key, err)
	}
	return nil
}

// HasNegativeCache implements port.CacheRepository.
// Checks if a negative cache key exists for a user.
// Returns true if cart was recently confirmed as non-existent (short-circuit).
func (r *CacheRepo) HasNegativeCache(ctx context.Context, userID string) (bool, error) {
	key := r.kb.NegativeCacheKey(userID)
	exists, err := r.client.Raw().Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("%w: exists %s: %v", port.ErrRedisConnectionFailed, key, err)
	}
	return exists > 0, nil
}

// SetItemAvailability implements port.CacheRepository.
// Updates only the is_available field of a cart item.
// Called when gRPC returns product BANNED/UNAVAILABLE.
//
// This is the ONLY exception to the "read-only TTL" rule for simple operations.
// It performs a targeted HSET to avoid full Lua script overhead for a single field update.
//
// IMPORTANT: This does NOT call EXPIRE. It only updates the field value.
// Caller is responsible for TTL management through CartLuaRepo write operations.
func (r *CacheRepo) SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error {
	err := r.client.Raw().HSet(ctx, cartKey, skuID, itemJSON).Err()
	if err != nil {
		return fmt.Errorf("%w: hset %s field %s: %v", port.ErrRedisConnectionFailed, cartKey, skuID, err)
	}
	return nil
}

// Ping implements port.CacheRepository.
// Checks Redis connectivity by executing PING.
func (r *CacheRepo) Ping(ctx context.Context) error {
	return r.client.Ping(ctx)
}

// HGetItem retrieves a single field from a cart hash.
// This is a convenience method not defined in the port interface.
// Used when you need a specific SKU without fetching the entire cart.
func (r *CacheRepo) HGetItem(ctx context.Context, cartKey, skuID string) (string, error) {
	result, err := r.client.Raw().HGet(ctx, cartKey, skuID).Result()
	if err == redis.Nil {
		return "", port.ErrNegativeCacheNotFound
	}
	if err != nil {
		return "", fmt.Errorf("%w: hget %s %s: %v", port.ErrRedisConnectionFailed, cartKey, skuID, err)
	}
	return result, nil
}

// Expire sets the TTL on a cart key.
// Use this only from controlled paths (e.g., rehydration complete, explicit refresh).
// Normal write operations should use CartLuaRepo which calls EXPIRE inside Lua scripts.
func (r *CacheRepo) Expire(ctx context.Context, cartKey string, ttlSeconds int) error {
	err := r.client.Raw().Expire(ctx, cartKey, negativeCacheDuration(ttlSeconds)).Err()
	if err != nil {
		return fmt.Errorf("%w: expire %s: %v", port.ErrRedisConnectionFailed, cartKey, err)
	}
	return nil
}

// negativeCacheDuration converts seconds to time.Duration.
func negativeCacheDuration(seconds int) time.Duration {
	if seconds <= 0 {
		return 60 * time.Second
	}
	return time.Duration(seconds) * time.Second
}

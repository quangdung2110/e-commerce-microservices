package redis_adapter

import (
	"context"
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// GraceCacheRepository implements repository.GraceCacheRepository using Redis
// Used during refresh token rotation to handle concurrent requests within 10-second window
type GraceCacheRepository struct {
	client *redis.Client
	prefix string
	ttl    time.Duration
}

// NewGraceCacheRepository creates a new GraceCacheRepository
func NewGraceCacheRepository(client *redis.Client) *GraceCacheRepository {
	return &GraceCacheRepository{
		client: client,
		prefix: domain.RedisKeyPrefixRefreshGrace,
		ttl:    domain.GracePeriodTTL,
	}
}

// key generates the Redis key for a token hash
func (r *GraceCacheRepository) key(tokenHash string) string {
	return r.prefix + tokenHash
}

// Set stores grace period data with TTL (10 seconds)
// This caches the new tokens generated during rotation for handling concurrent requests
func (r *GraceCacheRepository) Set(ctx context.Context, tokenHash string, data *types.GracePeriodData) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return r.client.Set(ctx, r.key(tokenHash), jsonData, r.ttl).Err()
}

// Get retrieves grace period data
func (r *GraceCacheRepository) Get(ctx context.Context, tokenHash string) (*types.GracePeriodData, error) {
	val, err := r.client.Get(ctx, r.key(tokenHash)).Result()
	if err != nil {
		if err == redis.Nil {
			return nil, nil // Not found
		}
		return nil, err
	}

	var data types.GracePeriodData
	if err := json.Unmarshal([]byte(val), &data); err != nil {
		return nil, err
	}
	return &data, nil
}

// Delete removes grace period data
func (r *GraceCacheRepository) Delete(ctx context.Context, tokenHash string) error {
	return r.client.Del(ctx, r.key(tokenHash)).Err()
}

// Exists checks if grace period data exists
func (r *GraceCacheRepository) Exists(ctx context.Context, tokenHash string) (bool, error) {
	exists, err := r.client.Exists(ctx, r.key(tokenHash)).Result()
	if err != nil {
		return false, err
	}
	return exists > 0, nil
}

// GetTTL returns the remaining TTL for a grace period cache entry
func (r *GraceCacheRepository) GetTTL(ctx context.Context, tokenHash string) (time.Duration, error) {
	return r.client.TTL(ctx, r.key(tokenHash)).Result()
}

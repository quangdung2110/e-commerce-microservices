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
	ttl    time.Duration
}

// NewGraceCacheRepository creates a new GraceCacheRepository
func NewGraceCacheRepository(client *redis.Client) *GraceCacheRepository {
	return &GraceCacheRepository{
		client: client,
		ttl:    domain.GracePeriodTTL,
	}
}

// key generates the Redis key for a token hash
func (r *GraceCacheRepository) key(tokenHash string) string {
	return domain.RedisKeyPrefixRefreshGrace + tokenHash
}

// Set stores grace period data with TTL (10 seconds)
func (r *GraceCacheRepository) Set(ctx context.Context, tokenHash string, data *types.GracePeriodData) error {
	data.CreatedAt = time.Now().Unix()
	data.ExpiresAt = time.Now().Add(r.ttl).Unix()

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
			return nil, nil
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
	result, err := r.client.Exists(ctx, r.key(tokenHash)).Result()
	if err != nil {
		return false, err
	}
	return result > 0, nil
}

// SetWithCustomTTL sets grace period data with a custom TTL
func (r *GraceCacheRepository) SetWithCustomTTL(ctx context.Context, tokenHash string, data *types.GracePeriodData, ttl time.Duration) error {
	data.CreatedAt = time.Now().Unix()
	data.ExpiresAt = time.Now().Add(ttl).Unix()

	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}

	return r.client.Set(ctx, r.key(tokenHash), jsonData, ttl).Err()
}

// Ping checks if Redis connection is alive
func (r *GraceCacheRepository) Ping(ctx context.Context) error {
	return r.client.Ping(ctx).Err()
}

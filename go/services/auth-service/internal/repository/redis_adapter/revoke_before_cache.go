package redis_adapter

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
)

// RevokeBeforeCacheRepository implements repository.RevokeBeforeCacheRepository using Redis
// Used for force logout all sessions - sets timestamp when all tokens should be revoked
type RevokeBeforeCacheRepository struct {
	client *redis.Client
	ttl    time.Duration
}

// NewRevokeBeforeCacheRepository creates a new RevokeBeforeCacheRepository
func NewRevokeBeforeCacheRepository(client *redis.Client) *RevokeBeforeCacheRepository {
	return &RevokeBeforeCacheRepository{
		client: client,
		ttl:    domain.RevokedBeforeTTL,
	}
}

// key generates the Redis key for a user ID
func (r *RevokeBeforeCacheRepository) key(userID string) string {
	return domain.RedisKeyPrefixRevokedBefore + userID
}

// SetRevokedBefore sets the timestamp when all tokens should be considered revoked
func (r *RevokeBeforeCacheRepository) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
	epoch := timestamp.Unix()
	return r.client.Set(ctx, r.key(userID), epoch, r.ttl).Err()
}

// GetRevokedBefore gets the revoke-before timestamp
func (r *RevokeBeforeCacheRepository) GetRevokedBefore(ctx context.Context, userID string) (*time.Time, error) {
	val, err := r.client.Get(ctx, r.key(userID)).Int64()
	if err != nil {
		if err == redis.Nil {
			return nil, nil
		}
		return nil, err
	}

	t := time.Unix(val, 0)
	return &t, nil
}

// IsRevokedBefore checks if a token issued at `iat` should be considered revoked
func (r *RevokeBeforeCacheRepository) IsRevokedBefore(ctx context.Context, userID string, iat time.Time) (bool, error) {
	val, err := r.client.Get(ctx, r.key(userID)).Int64()
	if err != nil {
		if err == redis.Nil {
			return false, nil
		}
		return false, err
	}

	revokedBefore := time.Unix(val, 0)
	return iat.Before(revokedBefore) || iat.Equal(revokedBefore), nil
}

// Delete removes the revoke-before entry
func (r *RevokeBeforeCacheRepository) Delete(ctx context.Context, userID string) error {
	return r.client.Del(ctx, r.key(userID)).Err()
}

// SetRevokedBeforeWithCustomTTL sets revoke-before with a custom TTL
func (r *RevokeBeforeCacheRepository) SetRevokedBeforeWithCustomTTL(ctx context.Context, userID string, timestamp time.Time, ttl time.Duration) error {
	epoch := timestamp.Unix()
	return r.client.Set(ctx, r.key(userID), epoch, ttl).Err()
}

// GetTTL returns the remaining TTL for a user's revoke-before entry
func (r *RevokeBeforeCacheRepository) GetTTL(ctx context.Context, userID string) (time.Duration, error) {
	return r.client.TTL(ctx, r.key(userID)).Result()
}

// Ping checks if Redis connection is alive
func (r *RevokeBeforeCacheRepository) Ping(ctx context.Context) error {
	return r.client.Ping(ctx).Err()
}

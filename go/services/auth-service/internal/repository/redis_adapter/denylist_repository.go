package redis_adapter

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
)

// DenylistRepository implements denylist operations for access tokens using Redis
// Used to track revoked access tokens (by JTI) for immediate invalidation
type DenylistRepository struct {
	client *redis.Client
	prefix string
}

// NewDenylistRepository creates a new DenylistRepository
func NewDenylistRepository(client *redis.Client) *DenylistRepository {
	return &DenylistRepository{
		client: client,
		prefix: domain.RedisKeyPrefixDenylist,
	}
}

// key generates the Redis key for a JTI
func (r *DenylistRepository) key(jti string) string {
	return r.prefix + jti
}

// AddToDenylist adds a JTI to the denylist with TTL
// TTL should be the remaining time until the token's natural expiry
func (r *DenylistRepository) AddToDenylist(ctx context.Context, jti string, remainingSeconds int64) error {
	if remainingSeconds <= 0 {
		remainingSeconds = int64(domain.AccessTokenTTL.Seconds())
	}
	return r.client.Set(ctx, r.key(jti), "1", time.Duration(remainingSeconds)*time.Second).Err()
}

// IsDenied checks if a JTI is in the denylist
func (r *DenylistRepository) IsDenied(ctx context.Context, jti string) (bool, error) {
	exists, err := r.client.Exists(ctx, r.key(jti)).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check denylist: %w", err)
	}
	return exists > 0, nil
}

// RemoveFromDenylist removes a JTI from the denylist (rarely used)
func (r *DenylistRepository) RemoveFromDenylist(ctx context.Context, jti string) error {
	return r.client.Del(ctx, r.key(jti)).Err()
}

// AddManyToDenylist adds multiple JTIs to the denylist
func (r *DenylistRepository) AddManyToDenylist(ctx context.Context, jtis []string, ttl time.Duration) error {
	if len(jtis) == 0 {
		return nil
	}

	pipe := r.client.Pipeline()
	for _, jti := range jtis {
		pipe.Set(ctx, r.key(jti), "1", ttl)
	}
	_, err := pipe.Exec(ctx)
	return err
}

// Count returns the number of entries in the denylist
func (r *DenylistRepository) Count(ctx context.Context) (int64, error) {
	count, err := r.client.DBSize(ctx).Result()
	return count, err
}

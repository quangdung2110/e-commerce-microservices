package redis_adapter

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
)

// DistributedLockRepository implements repository.DistributedLockRepository using Redis
// Uses SET NX EX pattern for atomic lock acquisition
type DistributedLockRepository struct {
	client *redis.Client
}

// NewDistributedLockRepository creates a new DistributedLockRepository
func NewDistributedLockRepository(client *redis.Client) *DistributedLockRepository {
	return &DistributedLockRepository{
		client: client,
	}
}

// key generates the Redis key for a lock
func (r *DistributedLockRepository) key(lockKey string) string {
	return domain.RedisKeyPrefixLock + lockKey
}

// Acquire attempts to acquire a lock with the given key and TTL
// Returns true if lock was acquired, false otherwise
// Uses SET NX EX for atomic operation
func (r *DistributedLockRepository) Acquire(ctx context.Context, lockKey string, ttl time.Duration) (bool, error) {
	// Use SET NX EX for atomic lock acquisition
	result, err := r.client.SetNX(ctx, r.key(lockKey), "locked", ttl).Result()
	if err != nil {
		return false, err
	}
	return result, nil
}

// Release releases a lock
func (r *DistributedLockRepository) Release(ctx context.Context, lockKey string) error {
	return r.client.Del(ctx, r.key(lockKey)).Err()
}

// Extend extends the TTL of an existing lock
func (r *DistributedLockRepository) Extend(ctx context.Context, lockKey string, ttl time.Duration) error {
	// Check if lock exists and extend
	exists, err := r.client.Exists(ctx, r.key(lockKey)).Result()
	if err != nil {
		return err
	}
	if exists == 0 {
		return ErrLockNotFound
	}
	return r.client.Expire(ctx, r.key(lockKey), ttl).Err()
}

// TryAcquireWithRetry attempts to acquire a lock with retries
func (r *DistributedLockRepository) TryAcquireWithRetry(ctx context.Context, lockKey string, ttl time.Duration, maxRetries int, retryInterval time.Duration) (bool, error) {
	for i := 0; i < maxRetries; i++ {
		acquired, err := r.Acquire(ctx, lockKey, ttl)
		if err != nil {
			return false, err
		}
		if acquired {
			return true, nil
		}

		// Wait before retry
		select {
		case <-ctx.Done():
			return false, ctx.Err()
		case <-time.After(retryInterval):
			continue
		}
	}
	return false, nil
}

// IsLocked checks if a lock is currently held
func (r *DistributedLockRepository) IsLocked(ctx context.Context, lockKey string) (bool, error) {
	exists, err := r.client.Exists(ctx, r.key(lockKey)).Result()
	if err != nil {
		return false, err
	}
	return exists > 0, nil
}

// Ping checks if Redis connection is alive
func (r *DistributedLockRepository) Ping(ctx context.Context) error {
	return r.client.Ping(ctx).Err()
}

// Custom errors
var (
	ErrLockNotFound = &LockError{Message: "lock not found"}
)

// LockError represents a lock-related error
type LockError struct {
	Message string
}

func (e *LockError) Error() string {
	return e.Message
}

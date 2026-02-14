package redis_adapter

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"go.uber.org/zap"
)

// RedisRateLimitRepository implements RateLimitRepository using Redis
type RedisRateLimitRepository struct {
	client *redis.Client
	logger *zap.Logger
}

// NewRedisRateLimitRepository creates new Redis rate limit repository
func NewRedisRateLimitRepository(client *redis.Client, logger *zap.Logger) repository.RateLimitRepository {
	return &RedisRateLimitRepository{
		client: client,
		logger: logger,
	}
}

// IncrementLoginAttempts increments login attempt counter for IP
func (r *RedisRateLimitRepository) IncrementLoginAttempts(ctx context.Context, ip string) (int, error) {
	key := fmt.Sprintf("ratelimit:login:%s", ip)

	count, err := r.client.Incr(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to increment login attempts: %w", err)
	}

	// Set expiry on first increment
	if count == 1 {
		r.client.Expire(ctx, key, 15*time.Minute)
	}

	return int(count), nil
}

// IncrementOTPRequests increments OTP request counter
func (r *RedisRateLimitRepository) IncrementOTPRequests(ctx context.Context, key string) (int, error) {
	count, err := r.client.Incr(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to increment OTP requests: %w", err)
	}

	// Set expiry on first increment (24 hours for daily limit)
	if count == 1 {
		r.client.Expire(ctx, key, 24*time.Hour)
	}

	return int(count), nil
}

// BlockIP blocks an IP address
func (r *RedisRateLimitRepository) BlockIP(ctx context.Context, ip string, reason string, ttl time.Duration) error {
	key := fmt.Sprintf("blocked:ip:%s", ip)

	if err := r.client.Set(ctx, key, reason, ttl).Err(); err != nil {
		return fmt.Errorf("failed to block IP: %w", err)
	}

	r.logger.Warn("IP blocked",
		zap.String("ip", ip),
		zap.String("reason", reason),
		zap.Duration("ttl", ttl))

	return nil
}

// IsIPBlocked checks if IP is blocked
func (r *RedisRateLimitRepository) IsIPBlocked(ctx context.Context, ip string) (bool, error) {
	key := fmt.Sprintf("blocked:ip:%s", ip)

	exists, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check IP block: %w", err)
	}

	return exists > 0, nil
}

// BlockUser blocks a user
func (r *RedisRateLimitRepository) BlockUser(ctx context.Context, userID uuid.UUID, reason string, ttl time.Duration) error {
	key := fmt.Sprintf("blocked:user:%s", userID.String())

	if err := r.client.Set(ctx, key, reason, ttl).Err(); err != nil {
		return fmt.Errorf("failed to block user: %w", err)
	}

	r.logger.Warn("User blocked",
		zap.String("user_id", userID.String()),
		zap.String("reason", reason),
		zap.Duration("ttl", ttl))

	return nil
}

// IsUserBlocked checks if user is blocked
func (r *RedisRateLimitRepository) IsUserBlocked(ctx context.Context, userID uuid.UUID) (bool, error) {
	key := fmt.Sprintf("blocked:user:%s", userID.String())

	exists, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check user block: %w", err)
	}

	return exists > 0, nil
}

// Delete removes a key from Redis
func (r *RedisRateLimitRepository) Delete(ctx context.Context, key string) error {
	return r.client.Del(ctx, key).Err()
}

// CheckRateLimit checks if rate limit is exceeded using token bucket algorithm
// Returns: allowed, remaining tokens, lastSeen timestamp, error
func (r *RedisRateLimitRepository) CheckRateLimit(ctx context.Context, key string, limit int64) (bool, int64, int64, error) {
	countKey := fmt.Sprintf("ratelimit:count:%s", key)
	lastSeenKey := fmt.Sprintf("ratelimit:lastseen:%s", key)

	// Get current count
	count, err := r.client.Get(ctx, countKey).Int64()
	if err != nil && err != redis.Nil {
		return false, 0, 0, fmt.Errorf("failed to get rate limit count: %w", err)
	}
	if err == redis.Nil {
		count = 0
	}

	// Get last seen
	lastSeen, err := r.client.Get(ctx, lastSeenKey).Int64()
	if err != nil && err != redis.Nil {
		return false, 0, 0, fmt.Errorf("failed to get last seen: %w", err)
	}
	if err == redis.Nil {
		lastSeen = 0
	}

	// Check if limit exceeded
	if count >= limit {
		return false, limit - count, lastSeen, nil
	}

	return true, limit - count, lastSeen, nil
}

// IncrementRequestCount increments request count for rate limiting
func (r *RedisRateLimitRepository) IncrementRequestCount(ctx context.Context, key string, window time.Duration) error {
	countKey := fmt.Sprintf("ratelimit:count:%s", key)

	count, err := r.client.Incr(ctx, countKey).Result()
	if err != nil {
		return fmt.Errorf("failed to increment request count: %w", err)
	}

	// Set expiry on first increment
	if count == 1 {
		r.client.Expire(ctx, countKey, window)
	}

	return nil
}

// IncrementSuppressedCount increments suppressed log count
func (r *RedisRateLimitRepository) IncrementSuppressedCount(ctx context.Context, key string) (int64, error) {
	suppressedKey := fmt.Sprintf("ratelimit:suppressed:%s", key)

	count, err := r.client.Incr(ctx, suppressedKey).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to increment suppressed count: %w", err)
	}

	if count == 1 {
		// Set TTL equal to log window
		r.client.Expire(ctx, suppressedKey, 5*time.Second)
	}

	return count, nil
}

// GetAndResetSuppressedCount gets and resets suppressed count atomically
func (r *RedisRateLimitRepository) GetAndResetSuppressedCount(ctx context.Context, key string) (int64, error) {
	suppressedKey := fmt.Sprintf("ratelimit:suppressed:%s", key)

	// Use GetDel to atomically get and delete
	count, err := r.client.GetDel(ctx, suppressedKey).Int64()
	if err != nil && err != redis.Nil {
		return 0, fmt.Errorf("failed to get suppressed count: %w", err)
	}

	if err == redis.Nil {
		return 0, nil
	}

	return count, nil
}

// UpdateLastSeen updates last seen timestamp
func (r *RedisRateLimitRepository) UpdateLastSeen(ctx context.Context, key string, timestamp int64) error {
	lastSeenKey := fmt.Sprintf("ratelimit:lastseen:%s", key)

	err := r.client.Set(ctx, lastSeenKey, timestamp, time.Minute).Err()
	if err != nil {
		return fmt.Errorf("failed to update last seen: %w", err)
	}

	return nil
}

// GetLastSeen gets last seen timestamp
func (r *RedisRateLimitRepository) GetLastSeen(ctx context.Context, key string) (int64, error) {
	lastSeenKey := fmt.Sprintf("ratelimit:lastseen:%s", key)

	timestamp, err := r.client.Get(ctx, lastSeenKey).Int64()
	if err != nil && err != redis.Nil {
		return 0, fmt.Errorf("failed to get last seen: %w", err)
	}

	if err == redis.Nil {
		return 0, nil
	}

	return timestamp, nil
}

// CheckPasswordResetRateLimit checks if password reset rate limit is exceeded (1/week)
// Returns: allowed (bool), time remaining until reset (Duration), error
func (r *RedisRateLimitRepository) CheckPasswordResetRateLimit(ctx context.Context, identifier string) (bool, time.Duration, error) {
	key := fmt.Sprintf("ratelimit:password_reset:%s", identifier)

	exists, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, 0, fmt.Errorf("failed to check password reset rate limit: %w", err)
	}

	if exists > 0 {
		// Get TTL
		ttl, err := r.client.TTL(ctx, key).Result()
		if err != nil {
			return false, 0, fmt.Errorf("failed to get TTL: %w", err)
		}
		return false, ttl, nil
	}

	return true, 0, nil
}

// SetPasswordResetRateLimit sets password reset rate limit (usually 1 week)
func (r *RedisRateLimitRepository) SetPasswordResetRateLimit(ctx context.Context, identifier string, ttl time.Duration) error {
	key := fmt.Sprintf("ratelimit:password_reset:%s", identifier)

	err := r.client.Set(ctx, key, time.Now().Unix(), ttl).Err()
	if err != nil {
		return fmt.Errorf("failed to set password reset rate limit: %w", err)
	}

	r.logger.Info("Password reset rate limit set",
		zap.String("identifier", identifier),
		zap.Duration("ttl", ttl))

	return nil
}

// SetActivityFlag sets activity flag for session (to reduce DB writes)
func (r *RedisRateLimitRepository) SetActivityFlag(ctx context.Context, sessionID uuid.UUID, ttl time.Duration) error {
	key := fmt.Sprintf("activity_flag:%s", sessionID.String())

	err := r.client.Set(ctx, key, time.Now().Unix(), ttl).Err()
	if err != nil {
		return fmt.Errorf("failed to set activity flag: %w", err)
	}

	return nil
}

// CheckActivityFlag checks if activity flag exists
func (r *RedisRateLimitRepository) CheckActivityFlag(ctx context.Context, sessionID uuid.UUID) (bool, error) {
	key := fmt.Sprintf("activity_flag:%s", sessionID.String())

	exists, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check activity flag: %w", err)
	}

	return exists > 0, nil
}

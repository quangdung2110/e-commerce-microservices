package redis_adapter

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"go.uber.org/zap"
)

// RedisTokenBlacklistRepository implements TokenBlacklistRepository using Redis
type RedisBlacklistRepository struct {
	client *redis.Client
	logger *zap.Logger
}

// NewRedisBlacklistRepository creates new Redis blacklist repository
func NewRedisBlacklistRepository(client *redis.Client, logger *zap.Logger) repository.BlacklistRepository {
	return &RedisBlacklistRepository{
		client: client,
		logger: logger,
	}
}

// Add adds a token (by JTI) to the blacklist with TTL
// TTL should be the remaining time until the token's natural expiry
func (r *RedisBlacklistRepository) Add(ctx context.Context, typ types.BlacklistType, parameter string, ttl time.Duration) error {
	var key string
	switch typ {
	case types.TokenBlacklist:
		key = r.buildTokenKey(parameter)
	case types.UserBlacklist:
		key = r.buildUserKey(parameter)
	default:
		return fmt.Errorf("unknown blacklist type: %s", typ)
	}

	// Value doesn't matter, just the existence of the key
	if err := r.client.Set(ctx, key, time.Now().Unix(), ttl).Err(); err != nil {
		return fmt.Errorf("failed to add token to blacklist: %w", err)
	}

	r.logger.Debug("Token added to blacklist",
		zap.String("type", string(typ)),
		zap.String("parameter", parameter),
		zap.Duration("ttl", ttl))

	return nil
}

// IsBlacklisted checks if a token (by JTI) is blacklisted
func (r *RedisBlacklistRepository) IsBlacklisted(ctx context.Context, typ types.BlacklistType, parameter string) (bool, error) {
	var key string
	switch typ {
	case types.TokenBlacklist:
		key = r.buildTokenKey(parameter)
	case types.UserBlacklist:
		key = r.buildUserKey(parameter)
	default:
		return false, fmt.Errorf("unknown blacklist type: %s", typ)
	}

	exists, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check blacklist: %w", err)
	}

	return exists > 0, nil
}

func (r *RedisBlacklistRepository) Get(ctx context.Context, typ types.BlacklistType, parameter string) (string, error) {
	var key string
	switch typ {
	case types.TokenBlacklist:
		key = r.buildTokenKey(parameter)
	case types.UserBlacklist:
		key = r.buildUserKey(parameter)
	default:
		return "", fmt.Errorf("unknown blacklist type: %s", typ)
	}

	data, err := r.client.Get(ctx, key).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return "", nil
		}
		return "", fmt.Errorf("failed to get blacklist status: %w", err)
	}

	return data, nil
}

// Remove removes a token from the blacklist (rarely used, mainly for testing)
func (r *RedisBlacklistRepository) Remove(ctx context.Context, typ types.BlacklistType, parameter string) error {
	var key string
	switch typ {
	case types.TokenBlacklist:
		key = r.buildTokenKey(parameter)
	case types.UserBlacklist:
		key = r.buildUserKey(parameter)
	default:
		return fmt.Errorf("unknown blacklist type: %s", typ)
	}

	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to remove token from blacklist: %w", err)
	}

	r.logger.Debug("Token removed from blacklist",
		zap.String("type", string(typ)),
		zap.String("parameter", parameter))

	return nil
}

// buildTokenKey constructs the Redis key for token blacklist
func (r *RedisBlacklistRepository) buildTokenKey(jti string) string {
	return fmt.Sprintf("blacklist:token:%s", jti)
}

func (r *RedisBlacklistRepository) buildUserKey(userID string) string {
	return fmt.Sprintf("blacklist:user:%s", userID)
}

package redis_adapter

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"go.uber.org/zap"
)

// RedisRefreshTokenRepository implements RefreshTokenRepository using Redis
type RedisRefreshTokenRepository struct {
	client *redis.Client
	logger *zap.Logger
}

// NewRedisRefreshTokenRepository creates new Redis refresh token repository
func NewRedisRefreshTokenRepository(client *redis.Client, logger *zap.Logger) repository.RefreshTokenRepository {
	return &RedisRefreshTokenRepository{
		client: client,
		logger: logger,
	}
}

// Store saves refresh token with metadata
func (r *RedisRefreshTokenRepository) Store(ctx context.Context, userID uuid.UUID, deviceID string, tokenHash string, sessionID uuid.UUID, ttl time.Duration) error {
	key := r.buildKey(userID, deviceID)

	data := &entity.RefreshTokenData{
		TokenHash: tokenHash,
		SessionID: sessionID.String(),
		CreatedAt: time.Now().UTC().Unix(),
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("failed to marshal token data: %w", err)
	}

	if err := r.client.Set(ctx, key, jsonData, ttl).Err(); err != nil {
		return fmt.Errorf("failed to store refresh token: %w", err)
	}

	r.logger.Debug("Refresh token stored",
		zap.String("user_id", userID.String()),
		zap.String("device_id", deviceID))

	return nil
}

// Find retrieves refresh token info
func (r *RedisRefreshTokenRepository) Get(ctx context.Context, userID uuid.UUID, deviceID string) (*entity.RefreshTokenData, error) {
	key := r.buildKey(userID, deviceID)

	data, err := r.client.Get(ctx, key).Result()
	if err == redis.Nil {
		return nil, fmt.Errorf("refresh token not found")
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get refresh token: %w", err)
	}

	var tokenData entity.RefreshTokenData
	if err := json.Unmarshal([]byte(data), &tokenData); err != nil {
		return nil, fmt.Errorf("failed to unmarshal token data: %w", err)
	}

	return &tokenData, nil
}

// Delete removes refresh token and optionally revokes the session
func (r *RedisRefreshTokenRepository) Delete(ctx context.Context, userID uuid.UUID, deviceID string) error {
	key := r.buildKey(userID, deviceID)

	// Delete from Redis
	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete refresh token: %w", err)
	}

	r.logger.Debug("Refresh token deleted",
		zap.String("user_id", userID.String()),
		zap.String("device_id", deviceID))

	return nil
}

// DeleteAllByUser removes all refresh tokens for user and revokes all sessions
func (r *RedisRefreshTokenRepository) DeleteAllByUserID(ctx context.Context, userID uuid.UUID) error {
	pattern := fmt.Sprintf("refresh:%s:*", userID.String())

	keys, err := r.client.Keys(ctx, pattern).Result()
	if err != nil {
		return fmt.Errorf("failed to find tokens: %w", err)
	}

	if len(keys) > 0 {
		if err := r.client.Del(ctx, keys...).Err(); err != nil {
			return fmt.Errorf("failed to delete tokens: %w", err)
		}
	}

	r.logger.Info("All refresh tokens deleted for user",
		zap.String("user_id", userID.String()),
		zap.Int("count", len(keys)))

	return nil
}

// UpdateRotation updates token after rotation
func (r *RedisRefreshTokenRepository) UpdateRotation(ctx context.Context, userID uuid.UUID, deviceID string, newTokenHash string, oldTokenHash string) (int64, error) {
	key := r.buildKey(userID, deviceID)

	// Get existing data
	existing, err := r.Get(ctx, userID, deviceID)
	if err != nil {
		return 0, err
	}

	// Update with new token and save old token for grace period
	existing.TokenHash = newTokenHash
	existing.RotatedFrom = &oldTokenHash
	now := time.Now().UTC().Unix()
	existing.RotatedAt = &now

	jsonData, err := json.Marshal(existing)
	if err != nil {
		return 0, fmt.Errorf("failed to marshal token data: %w", err)
	}

	// Keep same TTL
	ttl, err := r.client.TTL(ctx, key).Result()
	if err != nil {
		ttl = 90 * 24 * time.Hour // Default 90 days
	}

	if err := r.client.Set(ctx, key, jsonData, ttl).Err(); err != nil {
		return 0, fmt.Errorf("failed to update refresh token: %w", err)
	}

	return int64(ttl), nil
}

// buildKey builds Redis key for refresh token
func (r *RedisRefreshTokenRepository) buildKey(userID uuid.UUID, deviceID string) string {
	return fmt.Sprintf("refresh:%s:%s", userID.String(), deviceID)
}

package redis_adapter

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"go.uber.org/zap"
)

// RedisOTPRepository implements OTPRepository using Redis
type RedisOTPRepository struct {
	client *redis.Client
	logger *zap.Logger
}

// NewRedisOTPRepository creates new Redis OTP repository
func NewRedisOTPRepository(client *redis.Client, logger *zap.Logger) repository.OTPRepository {
	return &RedisOTPRepository{
		client: client,
		logger: logger,
	}
}

// Store saves OTP with TTL
func (r *RedisOTPRepository) Store(ctx context.Context, otp *entity.OTP, ttl time.Duration) error {
	key := r.buildKey(otp.Purpose, otp.Channel, otp.IdentifierHash)

	data, err := json.Marshal(otp)
	if err != nil {
		return fmt.Errorf("failed to marshal OTP: %w", err)
	}

	if err := r.client.Set(ctx, key, data, ttl).Err(); err != nil {
		return fmt.Errorf("failed to store OTP: %w", err)
	}

	r.logger.Debug("OTP stored",
		zap.String("purpose", string(otp.Purpose)),
		zap.String("channel", string(otp.Channel)),
		zap.Duration("ttl", ttl))

	return nil
}

// Find retrieves OTP by purpose and identifier hash
func (r *RedisOTPRepository) Get(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifierHash string) (*entity.OTP, error) {
	key := r.buildKey(purpose, channel, identifierHash)

	data, err := r.client.Get(ctx, key).Result()
	if err == redis.Nil {
		return nil, fmt.Errorf("OTP not found")
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get OTP: %w", err)
	}

	var otp entity.OTP
	if err := json.Unmarshal([]byte(data), &otp); err != nil {
		return nil, fmt.Errorf("failed to unmarshal OTP: %w", err)
	}

	return &otp, nil
}

// Delete removes OTP after successful verification
func (r *RedisOTPRepository) Delete(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifierHash string) error {
	key := r.buildKey(purpose, channel, identifierHash)

	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete OTP: %w", err)
	}

	return nil
}

// IncrementAttempts increments failed verification attempts
func (r *RedisOTPRepository) IncrementAttempts(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifierHash string) error {
	key := r.buildKey(purpose, channel, identifierHash)

	data, err := r.client.Get(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to get OTP: %w", err)
	}

	var otp entity.OTP
	if err := json.Unmarshal([]byte(data), &otp); err != nil {
		return fmt.Errorf("failed to unmarshal OTP: %w", err)
	}

	otp.IncrementAttempts()

	updatedData, err := json.Marshal(otp)
	if err != nil {
		return fmt.Errorf("failed to marshal OTP: %w", err)
	}

	ttl := time.Until(otp.ExpiresAt)
	if ttl < 0 {
		return fmt.Errorf("OTP expired")
	}

	if err := r.client.Set(ctx, key, updatedData, ttl).Err(); err != nil {
		return fmt.Errorf("failed to update OTP: %w", err)
	}

	return nil
}

// buildKey builds Redis key for OTP
func (r *RedisOTPRepository) buildKey(purpose entity.OTPPurpose, channel entity.OTPChannel, identifierHash string) string {
	return fmt.Sprintf("otp:%s:%s:%s", channel, purpose, identifierHash)
}

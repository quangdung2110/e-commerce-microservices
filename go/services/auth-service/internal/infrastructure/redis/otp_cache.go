package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

const OTPTTL = 5 * time.Minute

type OTPCache struct {
	client *Client
}

func NewOTPCache(client *Client) *OTPCache {
	return &OTPCache{client: client}
}

func (c *OTPCache) Set(ctx context.Context, email string, otp string) error {
	key := keybuilder.OTPEmail(email)

	_, err := c.client.Set(ctx, key, otp, OTPTTL).Result()
	if err != nil {
		return fmt.Errorf("failed to set OTP: %w", err)
	}

	return nil
}

func (c *OTPCache) Get(ctx context.Context, email string) (string, error) {
	key := keybuilder.OTPEmail(email)

	otp, err := c.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return "", nil
		}
		return "", fmt.Errorf("failed to get OTP: %w", err)
	}

	return otp, nil
}

func (c *OTPCache) Delete(ctx context.Context, email string) error {
	key := keybuilder.OTPEmail(email)

	_, err := c.client.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to delete OTP: %w", err)
	}

	return nil
}

func (c *OTPCache) Verify(ctx context.Context, email string, otp string) (bool, error) {
	key := keybuilder.OTPEmail(email)

	script := redis.NewScript(`
		local stored = redis.call('GET', KEYS[1])
		if stored == ARGV[1] then
			redis.call('DEL', KEYS[1])
			return 1
		end
		return 0
	`)

	result, err := script.Run(ctx, c.client, []string{key}, otp).Int()
	if err != nil {
		return false, fmt.Errorf("failed to verify OTP: %w", err)
	}

	return result == 1, nil
}

func (c *OTPCache) GetRemainingTTL(ctx context.Context, email string) (time.Duration, error) {
	key := keybuilder.OTPEmail(email)

	ttl, err := c.client.TTL(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to get OTP TTL: %w", err)
	}

	return ttl, nil
}

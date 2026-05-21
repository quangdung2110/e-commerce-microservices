package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

const ResetTokenTTL = 15 * time.Minute

type ResetTokenCache struct {
	client *Client
}

func NewResetTokenCache(client *Client) *ResetTokenCache {
	return &ResetTokenCache{client: client}
}

func (c *ResetTokenCache) Set(ctx context.Context, tokenHash, userID string) error {
	key := keybuilder.ResetToken(tokenHash)

	_, err := c.client.Set(ctx, key, userID, ResetTokenTTL).Result()
	if err != nil {
		return fmt.Errorf("failed to set reset token: %w", err)
	}

	return nil
}

func (c *ResetTokenCache) Get(ctx context.Context, tokenHash string) (string, error) {
	key := keybuilder.ResetToken(tokenHash)

	userID, err := c.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return "", nil
		}
		return "", fmt.Errorf("failed to get reset token: %w", err)
	}

	return userID, nil
}

func (c *ResetTokenCache) Delete(ctx context.Context, tokenHash string) error {
	key := keybuilder.ResetToken(tokenHash)

	_, err := c.client.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to delete reset token: %w", err)
	}

	return nil
}

func (c *ResetTokenCache) Verify(ctx context.Context, tokenHash string) (string, error) {
	key := keybuilder.ResetToken(tokenHash)

	script := redis.NewScript(`
		local userID = redis.call('GET', KEYS[1])
		if userID then
			redis.call('DEL', KEYS[1])
		end
		return userID
	`)

	result, err := script.Run(ctx, c.client, []string{key}).Result()
	if err != nil && err != redis.Nil {
		return "", fmt.Errorf("failed to verify reset token: %w", err)
	}

	if result == nil {
		return "", nil
	}

	return result.(string), nil
}

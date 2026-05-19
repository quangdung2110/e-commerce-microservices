package redis

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

const FailedLoginTTL = 60 * time.Minute
const MaxFailedAttempts = 5

type FailedLoginCounter struct {
	client *Client
}

func NewFailedLoginCounter(client *Client) *FailedLoginCounter {
	return &FailedLoginCounter{client: client}
}

func (c *FailedLoginCounter) Increment(ctx context.Context, identifier string) (int, error) {
	key := keybuilder.FailedLoginEmail(identifier)

	count, err := c.client.Incr(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to increment failed login counter: %w", err)
	}

	if count == 1 {
		c.client.Expire(ctx, key, FailedLoginTTL)
	}

	return int(count), nil
}

func (c *FailedLoginCounter) GetCount(ctx context.Context, identifier string) (int, error) {
	key := keybuilder.FailedLoginEmail(identifier)

	val, err := c.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, fmt.Errorf("failed to get failed login count: %w", err)
	}

	count, err := strconv.Atoi(val)
	if err != nil {
		return 0, fmt.Errorf("failed to parse count: %w", err)
	}

	return count, nil
}

func (c *FailedLoginCounter) Reset(ctx context.Context, identifier string) error {
	key := keybuilder.FailedLoginEmail(identifier)

	_, err := c.client.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to reset failed login counter: %w", err)
	}

	return nil
}

func (c *FailedLoginCounter) GetRemainingTTL(ctx context.Context, identifier string) (time.Duration, error) {
	key := keybuilder.FailedLoginEmail(identifier)

	ttl, err := c.client.TTL(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to get TTL: %w", err)
	}

	return ttl, nil
}

func (c *FailedLoginCounter) IsLocked(ctx context.Context, identifier string) (bool, error) {
	count, err := c.GetCount(ctx, identifier)
	if err != nil {
		return false, err
	}

	return count >= MaxFailedAttempts, nil
}

func (c *FailedLoginCounter) IncrementAndCheck(ctx context.Context, identifier string) (int, bool, error) {
	count, err := c.Increment(ctx, identifier)
	if err != nil {
		return 0, false, err
	}

	shouldLock := count >= MaxFailedAttempts
	return count, shouldLock, nil
}

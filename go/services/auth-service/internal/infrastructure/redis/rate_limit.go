package redis

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

type RateLimit struct {
	client *Client
}

func NewRateLimit(client *Client) *RateLimit {
	return &RateLimit{client: client}
}

func (r *RateLimit) Allow(ctx context.Context, action, identifier string, limit int, window time.Duration) (bool, error) {
	key := keybuilder.RateLimit(action, identifier)

	count, err := r.client.Incr(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to increment rate limit counter: %w", err)
	}

	if count == 1 {
		r.client.Expire(ctx, key, window)
	}

	return count <= int64(limit), nil
}

func (r *RateLimit) GetCount(ctx context.Context, action, identifier string) (int, error) {
	key := keybuilder.RateLimit(action, identifier)

	val, err := r.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, fmt.Errorf("failed to get rate limit count: %w", err)
	}

	count, err := strconv.Atoi(val)
	if err != nil {
		return 0, fmt.Errorf("failed to parse count: %w", err)
	}

	return count, nil
}

func (r *RateLimit) Reset(ctx context.Context, action, identifier string) error {
	key := keybuilder.RateLimit(action, identifier)

	_, err := r.client.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to reset rate limit: %w", err)
	}

	return nil
}

func (r *RateLimit) GetRemainingTTL(ctx context.Context, action, identifier string) (time.Duration, error) {
	key := keybuilder.RateLimit(action, identifier)

	ttl, err := r.client.TTL(ctx, key).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to get TTL: %w", err)
	}

	return ttl, nil
}

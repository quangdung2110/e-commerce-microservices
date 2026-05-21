package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/config"
)

type Client struct {
	*redis.Client
}

func NewClient(cfg config.RedisConfig) (*Client, error) {
	client := redis.NewClient(&redis.Options{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     cfg.PoolSize,
		MinIdleConns: cfg.MinIdleConns,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	return &Client{Client: client}, nil
}

func (c *Client) Close() error {
	return c.Client.Close()
}

func (c *Client) Ping(ctx context.Context) *redis.StatusCmd {
	return c.Client.Ping(ctx)
}

func (c *Client) Pipeline() redis.Pipeliner {
	return c.Client.Pipeline()
}

func (c *Client) SetEX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.StatusCmd {
	return c.Client.SetEx(ctx, key, value, expiration)
}

func (c *Client) SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd {
	return c.Client.SetNX(ctx, key, value, expiration)
}

func (c *Client) SetXX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd {
	return c.Client.SetXX(ctx, key, value, expiration)
}

func (c *Client) Incr(ctx context.Context, key string) *redis.IntCmd {
	return c.Client.Incr(ctx, key)
}

func (c *Client) ZAdd(ctx context.Context, key string, members ...redis.Z) *redis.IntCmd {
	return c.Client.ZAdd(ctx, key, members...)
}

func (c *Client) ZCard(ctx context.Context, key string) *redis.IntCmd {
	return c.Client.ZCard(ctx, key)
}

func (c *Client) ZRemRangeByScore(ctx context.Context, key, min, max string) *redis.IntCmd {
	return c.Client.ZRemRangeByScore(ctx, key, min, max)
}

func (c *Client) Publish(ctx context.Context, channel string, message interface{}) *redis.IntCmd {
	return c.Client.Publish(ctx, channel, message)
}

func (c *Client) Subscribe(ctx context.Context, channels ...string) *redis.PubSub {
	return c.Client.Subscribe(ctx, channels...)
}

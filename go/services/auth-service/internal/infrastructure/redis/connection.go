package redis

import (
	"context"
	"fmt"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/redis/go-redis/v9"
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

	ctx := context.Background()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	return &Client{Client: client}, nil
}

func (c *Client) Close() error {
	return c.Client.Close()
}

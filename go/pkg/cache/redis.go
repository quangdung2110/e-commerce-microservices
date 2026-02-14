package cache

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

// RedisCacheManager implements CacheManager using Redis
type RedisCacheManager struct {
	client *redis.Client
	logger *zap.Logger
}

// RedisConfig holds Redis connection configuration
type RedisConfig struct {
	Host     string
	Port     int
	Password string
	DB       int
	Logger   *zap.Logger
}

// NewRedisCacheManager creates a new Redis cache manager
func NewRedisCacheManager(config RedisConfig) (*RedisCacheManager, error) {
	if config.Logger == nil {
		config.Logger, _ = zap.NewProduction()
	}

	client := redis.NewClient(&redis.Options{
		Addr:         fmt.Sprintf("%s:%d", config.Host, config.Port),
		Password:     config.Password,
		DB:           config.DB,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
		PoolSize:     10,
		MinIdleConns: 5,
	})

	// Test connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	config.Logger.Info("Connected to Redis",
		zap.String("host", config.Host),
		zap.Int("port", config.Port),
		zap.Int("db", config.DB))

	return &RedisCacheManager{
		client: client,
		logger: config.Logger,
	}, nil
}

// Ping checks if Redis connection is alive
func (c *RedisCacheManager) Ping(ctx context.Context) error {
	return c.client.Ping(ctx).Err()
}

// Close closes the Redis connection
func (c *RedisCacheManager) Close() error {
	c.logger.Info("Closing Redis connection")
	return c.client.Close()
}

// Client returns the underlying redis client for direct access
func (c *RedisCacheManager) Client() *redis.Client {
	return c.client
}

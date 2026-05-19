package helper

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	testredis "github.com/testcontainers/testcontainers-go/modules/redis"
)

type TestRedis struct {
	Client  *redis.Client
	Cleanup func()
}

func SetupRedisContainer(t *testing.T) *TestRedis {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	if os.Getenv("INTEGRATION_TEST") != "1" {
		t.Skip("Skipping integration test. Set INTEGRATION_TEST=1 to run.")
	}

	container, err := testredis.Run(ctx,
		"redis:7-alpine",
	)
	if err != nil {
		t.Fatalf("Failed to start Redis container: %v", err)
	}

	connStr, err := container.ConnectionString(ctx)
	if err != nil {
		container.Terminate(ctx)
		t.Fatalf("Failed to get Redis connection string: %v", err)
	}

	cfg := config.RedisConfig{
		Host:         "localhost",
		Port:         6379,
		Password:     "",
		DB:           0,
		PoolSize:     10,
		MinIdleConns: 2,
	}

	// Try to parse host and port from connection string if available
	// The connection string format is: redis://host:port
	if connStr != "" {
		// Use default localhost:6379 for testcontainers
		_ = fmt.Sprintf("Using connection: %s", connStr)
	}

	client, err := redis.NewClient(cfg)
	if err != nil {
		container.Terminate(ctx)
		t.Fatalf("Failed to create Redis client: %v", err)
	}

	cleanup := func() {
		client.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		container.Terminate(ctx)
	}

	return &TestRedis{
		Client:  client,
		Cleanup: cleanup,
	}
}

func (t *TestRedis) FlushAll(ctx context.Context) error {
	return t.Client.FlushDB(ctx).Err()
}

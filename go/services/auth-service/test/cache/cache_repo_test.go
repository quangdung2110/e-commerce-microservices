package cache_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCacheRepository_SetAndGet(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	cacheRepo := redis.NewCacheRepository(client)

	key := "test-key"
	value := "test-value"
	ttl := time.Hour

	err := cacheRepo.Set(ctx, key, value, ttl)
	require.NoError(t, err)

	retrieved, err := cacheRepo.Get(ctx, key)
	require.NoError(t, err)
	assert.Equal(t, value, retrieved)
}

func TestCacheRepository_Get_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	cacheRepo := redis.NewCacheRepository(client)

	_, err := cacheRepo.Get(ctx, "non-existent-key")
	assert.Error(t, err)
}

func TestCacheRepository_Delete(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	cacheRepo := redis.NewCacheRepository(client)

	key := "delete-key-unique"
	err := cacheRepo.Set(ctx, key, "value", time.Hour)
	require.NoError(t, err)

	err = cacheRepo.Delete(ctx, key)
	require.NoError(t, err)
}

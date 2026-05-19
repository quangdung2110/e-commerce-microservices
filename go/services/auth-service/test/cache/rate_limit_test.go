package cache_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRateLimit_Allow(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	rateLimit := redis.NewRateLimit(client)

	action := "login"
	identifier := "user-123"
	limit := 3
	window := time.Minute

	for i := 0; i < limit; i++ {
		allowed, err := rateLimit.Allow(ctx, action, identifier, limit, window)
		require.NoError(t, err)
		assert.True(t, allowed)
	}

	allowed, err := rateLimit.Allow(ctx, action, identifier, limit, window)
	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestRateLimit_GetCount(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	rateLimit := redis.NewRateLimit(client)

	action := "login"
	identifier := "user-count"
	limit := 5
	window := time.Minute

	_, err := rateLimit.Allow(ctx, action, identifier, limit, window)
	require.NoError(t, err)
	_, err = rateLimit.Allow(ctx, action, identifier, limit, window)
	require.NoError(t, err)

	count, err := rateLimit.GetCount(ctx, action, identifier)
	require.NoError(t, err)
	assert.Equal(t, 2, count)
}

func TestRateLimit_GetCount_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	rateLimit := redis.NewRateLimit(client)

	count, err := rateLimit.GetCount(ctx, "action", "non-existent")
	require.NoError(t, err)
	assert.Equal(t, 0, count)
}

func TestRateLimit_Reset(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	rateLimit := redis.NewRateLimit(client)

	action := "login"
	identifier := "user-reset"
	limit := 5
	window := time.Minute

	_, _ = rateLimit.Allow(ctx, action, identifier, limit, window)
	_, _ = rateLimit.Allow(ctx, action, identifier, limit, window)

	err := rateLimit.Reset(ctx, action, identifier)
	require.NoError(t, err)

	count, err := rateLimit.GetCount(ctx, action, identifier)
	require.NoError(t, err)
	assert.Equal(t, 0, count)
}

func TestRateLimit_GetRemainingTTL(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	rateLimit := redis.NewRateLimit(client)

	action := "login"
	identifier := "user-ttl"
	limit := 5
	window := 5 * time.Minute

	_, _ = rateLimit.Allow(ctx, action, identifier, limit, window)

	ttl, err := rateLimit.GetRemainingTTL(ctx, action, identifier)
	require.NoError(t, err)
	assert.True(t, ttl > 0)
	assert.True(t, ttl <= window)
}


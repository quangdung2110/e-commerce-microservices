package cache_test

import (
	"context"
	"testing"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestFailedLoginCounter_GetCount_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	counter := redis.NewFailedLoginCounter(client)

	count, err := counter.GetCount(ctx, "nonexistent@example.com")
	require.NoError(t, err)
	assert.Equal(t, 0, count)
}

func TestFailedLoginCounter_Reset(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	counter := redis.NewFailedLoginCounter(client)

	identifier := "reset-unique-" + t.Name()
	_, _ = counter.Increment(ctx, identifier)
	_, _ = counter.Increment(ctx, identifier)

	err := counter.Reset(ctx, identifier)
	require.NoError(t, err)

	count, err := counter.GetCount(ctx, identifier)
	require.NoError(t, err)
	assert.Equal(t, 0, count)
}

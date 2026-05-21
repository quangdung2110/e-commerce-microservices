package cache_test

import (
	"context"
	"testing"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestResetTokenCache_SetAndGet(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	resetCache := redis.NewResetTokenCache(client)

	tokenHash := "test-token-hash"
	userID := "user-123"

	err := resetCache.Set(ctx, tokenHash, userID)
	require.NoError(t, err)

	retrieved, err := resetCache.Get(ctx, tokenHash)
	require.NoError(t, err)
	assert.Equal(t, userID, retrieved)
}

func TestResetTokenCache_Get_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	resetCache := redis.NewResetTokenCache(client)

	retrieved, err := resetCache.Get(ctx, "non-existent-hash")
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

func TestResetTokenCache_Delete(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	resetCache := redis.NewResetTokenCache(client)

	tokenHash := "delete-token-hash"
	err := resetCache.Delete(ctx, tokenHash)
	require.NoError(t, err)

	retrieved, err := resetCache.Get(ctx, tokenHash)
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

func TestResetTokenCache_Verify(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	resetCache := redis.NewResetTokenCache(client)

	tokenHash := "verify-token-hash"
	userID := "user-verify"

	err := resetCache.Set(ctx, tokenHash, userID)
	require.NoError(t, err)

	retrieved, err := resetCache.Verify(ctx, tokenHash)
	require.NoError(t, err)
	assert.Equal(t, userID, retrieved)

	retrieved2, err := resetCache.Verify(ctx, tokenHash)
	require.NoError(t, err)
	assert.Empty(t, retrieved2)
}

func TestResetTokenCache_Verify_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	resetCache := redis.NewResetTokenCache(client)

	retrieved, err := resetCache.Verify(ctx, "non-existent-token")
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

package cache_test

import (
	"context"
	"testing"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGracePeriod_SetAndGet(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	gracePeriod := redis.NewGracePeriod(client)

	tokenHash := "test-hash-456"
	accessToken := "new-access-token"
	refreshToken := "new-refresh-token"

	err := gracePeriod.Set(ctx, tokenHash, accessToken, refreshToken)
	require.NoError(t, err)

	gotAccess, gotRefresh, found, err := gracePeriod.Get(ctx, tokenHash)
	require.NoError(t, err)
	assert.True(t, found)
	assert.Equal(t, accessToken, gotAccess)
	assert.Equal(t, refreshToken, gotRefresh)
}

func TestGracePeriod_Get_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	gracePeriod := redis.NewGracePeriod(client)

	_, _, found, err := gracePeriod.Get(ctx, "non-existent-hash")
	require.NoError(t, err)
	assert.False(t, found)
}

func TestGracePeriod_Delete(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	gracePeriod := redis.NewGracePeriod(client)

	tokenHash := "delete-test-hash"
	err := gracePeriod.Set(ctx, tokenHash, "access", "refresh")
	require.NoError(t, err)

	err = gracePeriod.Delete(ctx, tokenHash)
	require.NoError(t, err)

	_, _, found, err := gracePeriod.Get(ctx, tokenHash)
	require.NoError(t, err)
	assert.False(t, found)
}

func TestGracePeriod_SetWithResult(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	gracePeriod := redis.NewGracePeriod(client)

	tokenHash := "set-with-result-hash"
	accessToken := "access-token"
	refreshToken := "refresh-token"

	pair, err := gracePeriod.SetWithResult(ctx, tokenHash, accessToken, refreshToken)
	require.NoError(t, err)
	require.NotNil(t, pair)
	assert.Equal(t, accessToken, pair.AccessToken)
	assert.Equal(t, refreshToken, pair.RefreshToken)
}

func TestGracePeriod_SetWithResult_AlreadyExists(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	gracePeriod := redis.NewGracePeriod(client)

	tokenHash := "duplicate-hash"
	accessToken1 := "access-token-1"
	refreshToken1 := "refresh-token-1"

	pair1, err := gracePeriod.SetWithResult(ctx, tokenHash, accessToken1, refreshToken1)
	require.NoError(t, err)
	require.NotNil(t, pair1)

	accessToken2 := "access-token-2"
	refreshToken2 := "refresh-token-2"

	pair2, err := gracePeriod.SetWithResult(ctx, tokenHash, accessToken2, refreshToken2)
	require.NoError(t, err)
	require.NotNil(t, pair2)

	assert.Equal(t, accessToken1, pair2.AccessToken)
	assert.Equal(t, refreshToken1, pair2.RefreshToken)
}

package cache_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestOTPCache_SetAndGet(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	email := "test@example.com"
	otp := "123456"

	err := otpCache.Set(ctx, email, otp)
	require.NoError(t, err)

	retrieved, err := otpCache.Get(ctx, email)
	require.NoError(t, err)
	assert.Equal(t, otp, retrieved)
}

func TestOTPCache_Get_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	retrieved, err := otpCache.Get(ctx, "nonexistent@example.com")
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

func TestOTPCache_Delete(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	email := "delete@example.com"
	err := otpCache.Set(ctx, email, "654321")
	require.NoError(t, err)

	err = otpCache.Delete(ctx, email)
	require.NoError(t, err)

	retrieved, err := otpCache.Get(ctx, email)
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

func TestOTPCache_Verify(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	email := "verify@example.com"
	otp := "111222"

	err := otpCache.Set(ctx, email, otp)
	require.NoError(t, err)

	valid, err := otpCache.Verify(ctx, email, otp)
	require.NoError(t, err)
	assert.True(t, valid)

	retrieved, err := otpCache.Get(ctx, email)
	require.NoError(t, err)
	assert.Empty(t, retrieved)
}

func TestOTPCache_Verify_WrongOTP(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	email := "wrong@example.com"
	err := otpCache.Set(ctx, email, "123456")
	require.NoError(t, err)

	valid, err := otpCache.Verify(ctx, email, "654321")
	require.NoError(t, err)
	assert.False(t, valid)

	retrieved, err := otpCache.Get(ctx, email)
	require.NoError(t, err)
	assert.Equal(t, "123456", retrieved)
}

func TestOTPCache_GetRemainingTTL(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	otpCache := redis.NewOTPCache(client)

	email := "ttl@example.com"
	err := otpCache.Set(ctx, email, "123456")
	require.NoError(t, err)

	ttl, err := otpCache.GetRemainingTTL(ctx, email)
	require.NoError(t, err)
	assert.True(t, ttl > 0)
	assert.True(t, ttl <= 5*time.Minute)
}

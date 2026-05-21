package cache_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/ecommerce/auth-service/test/helper"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func setupRedisTest(t *testing.T) (*redis.Client, func()) {
	testRedis := helper.SetupRedisContainer(t)
	return testRedis.Client, testRedis.Cleanup
}

func TestDenylist_AddToDenylist(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	jti := "test-jti-123"
	ttl := 5 * time.Minute

	err := denylist.AddToDenylist(ctx, jti, ttl)
	require.NoError(t, err)

	isDenylisted, err := denylist.IsDenylisted(ctx, jti)
	require.NoError(t, err)
	assert.True(t, isDenylisted)
}

func TestDenylist_IsDenylisted_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	isDenylisted, err := denylist.IsDenylisted(ctx, "non-existent-jti")
	require.NoError(t, err)
	assert.False(t, isDenylisted)
}

func TestDenylist_SetAndGetRevokedBefore(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	userID := "user-123"
	timestamp := time.Now()

	err := denylist.SetRevokedBefore(ctx, userID, timestamp)
	require.NoError(t, err)

	retrieved, err := denylist.GetRevokedBefore(ctx, userID)
	require.NoError(t, err)

	assert.WithinDuration(t, timestamp, retrieved, time.Second)
}

func TestDenylist_GetRevokedBefore_NotFound(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	retrieved, err := denylist.GetRevokedBefore(ctx, "non-existent-user")
	require.NoError(t, err)
	assert.True(t, retrieved.IsZero())
}

func TestDenylist_BatchAddToDenylist(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	jtis := []string{"jti-1", "jti-2", "jti-3"}
	ttl := 5 * time.Minute

	err := denylist.BatchAddToDenylist(ctx, jtis, ttl)
	require.NoError(t, err)

	for _, jti := range jtis {
		isDenylisted, err := denylist.IsDenylisted(ctx, jti)
		require.NoError(t, err)
		assert.True(t, isDenylisted)
	}
}

func TestDenylist_AddToDenylist_ZeroTTL(t *testing.T) {
	client, cleanup := setupRedisTest(t)
	if client == nil {
		return
	}
	defer cleanup()

	ctx := context.Background()
	denylist := redis.NewDenylist(client)

	err := denylist.AddToDenylist(ctx, "jti-zero-ttl", 0)
	require.NoError(t, err)

	isDenylisted, err := denylist.IsDenylisted(ctx, "jti-zero-ttl")
	require.NoError(t, err)
	assert.False(t, isDenylisted)
}

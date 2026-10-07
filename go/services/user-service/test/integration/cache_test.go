//go:build integration

package integration_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/entity"
	redisadapter "github.com/ecommerce/user-service/internal/infrastructure/redis"
)

func newCache(ttl, emptyTTL time.Duration) *redisadapter.AddressCache {
	keys := keybuilder.New(testRedisConf.CachePrefix, testRedisConf.StatePrefix)
	return redisadapter.NewAddressCache(testRedis, keys, ttl, emptyTTL, zap.NewNop())
}

func TestAddressCacheRoundTrip(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	cache := newCache(12*time.Hour, 15*time.Minute)
	userID := uuid.New()

	_, found := cache.Get(ctx, userID)
	require.False(t, found, "an empty cache is a miss, not an empty list")

	address := newAddress(t, userID, true)
	require.NoError(t, cache.Put(ctx, userID, []*entity.Address{address}))

	cached, found := cache.Get(ctx, userID)
	require.True(t, found)
	require.Len(t, cached, 1)
	require.Equal(t, address.ID, cached[0].ID)
	require.Equal(t, userID, cached[0].UserID)
	require.Equal(t, address.RecipientPhone.String(), cached[0].RecipientPhone.String())
	require.True(t, address.CreatedAt.Equal(cached[0].CreatedAt))
	require.True(t, cached[0].IsDefault)

	require.NoError(t, cache.Invalidate(ctx, userID))
	_, found = cache.Get(ctx, userID)
	require.False(t, found)
}

func TestAddressCacheUsesTheDocumentedKey(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	cache := newCache(12*time.Hour, 15*time.Minute)
	userID := uuid.New()

	require.NoError(t, cache.Put(ctx, userID, nil))

	// docs/user/data.md §6 pins this key; changing it silently would strand
	// every value already in Redis.
	exists, err := testRedis.Exists(ctx, "user:caches:addresses:"+userID.String()).Result()
	require.NoError(t, err)
	require.Equal(t, int64(1), exists)
}

func TestEmptyListGetsTheShorterTTL(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	cache := newCache(12*time.Hour, 15*time.Minute)
	userID := uuid.New()

	require.NoError(t, cache.Put(ctx, userID, nil))
	emptyTTL, err := testRedis.TTL(ctx, "user:caches:addresses:"+userID.String()).Result()
	require.NoError(t, err)
	require.LessOrEqual(t, emptyTTL, 15*time.Minute)

	require.NoError(t, cache.Put(ctx, userID, []*entity.Address{newAddress(t, userID, true)}))
	listTTL, err := testRedis.TTL(ctx, "user:caches:addresses:"+userID.String()).Result()
	require.NoError(t, err)
	require.Greater(t, listTTL, 11*time.Hour)
}

func TestCorruptCacheContentIsTreatedAsAMiss(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	cache := newCache(12*time.Hour, 15*time.Minute)
	userID := uuid.New()

	require.NoError(t, testRedis.Set(ctx,
		"user:caches:addresses:"+userID.String(), "{not json", time.Minute).Err())

	// Unusable content must never reach a caller: the read falls back to
	// PostgreSQL instead.
	_, found := cache.Get(ctx, userID)
	require.False(t, found)
}

func TestCacheFromAnOlderSerializationIsIgnored(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	cache := newCache(12*time.Hour, 15*time.Minute)
	userID := uuid.New()

	require.NoError(t, testRedis.Set(ctx,
		"user:caches:addresses:"+userID.String(),
		`{"version":0,"addresses":[]}`, time.Minute).Err())

	_, found := cache.Get(ctx, userID)
	require.False(t, found, "a superseded document version is retired, not decoded")
}

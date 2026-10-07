//go:build integration

package redis

import (
	"context"
	"log"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	redismodule "github.com/testcontainers/testcontainers-go/modules/redis"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

const redisImage = "redis:7-alpine"

var testRedisConf config.RedisConfig

func TestMain(m *testing.M) {
	ctx := context.Background()
	container, err := redismodule.Run(ctx, redisImage)
	if err != nil {
		log.Fatalf("integration: start redis: %v", err)
	}
	host, err := container.Host(ctx)
	if err != nil {
		log.Fatalf("integration: redis host: %v", err)
	}
	mapped, err := container.MappedPort(ctx, "6379/tcp")
	if err != nil {
		log.Fatalf("integration: redis port: %v", err)
	}
	testRedisConf = config.RedisConfig{
		Host: host, Port: int(mapped.Num()), PoolSize: 10, MinIdleConns: 1,
		DialTimeout: 2 * time.Second, ReadTimeout: 2 * time.Second, WriteTimeout: 2 * time.Second,
		KeyPrefix: "itest",
	}
	code := m.Run()
	_ = container.Terminate(ctx)
	os.Exit(code)
}

func newTestClient(t *testing.T) *goredis.Client {
	t.Helper()
	client := NewClient(testRedisConf)
	require.NoError(t, Ping(context.Background(), client))
	require.NoError(t, client.FlushDB(context.Background()).Err())
	t.Cleanup(func() { _ = client.Close() })
	return client
}

func TestIntegration_TokenBucketGrantsAtRate(t *testing.T) {
	client := newTestClient(t)
	keys := NewKeys(testRedisConf.KeyPrefix)
	m := telemetry.New()
	// Two "replicas" share one bucket: rate 5/s, burst 5.
	rates := map[entity.Lane]float64{entity.LaneBulk: 5}
	a := NewTokenBucket(client, keys, clock.System{}, rates, 4, m, nil)
	b := NewTokenBucket(client, keys, clock.System{}, rates, 4, m, nil)

	ctx := context.Background()
	// Non-blocking polls for 1.2 s, alternating replicas: the burst of 5 is
	// granted at once, then ~5/s refill → between 5 and 7 of 10 requests
	// succeed inside a 300 ms window, and 10 need at least ~1 s.
	start := time.Now()
	granted := 0
	for i := 0; i < 10; i++ {
		limiter := a
		if i%2 == 1 {
			limiter = b
		}
		require.NoError(t, limiter.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 1200*time.Millisecond))
		granted++
		if granted == 7 {
			assert.GreaterOrEqual(t, time.Since(start), 300*time.Millisecond, "7th token needs ≥ 2 refills at 5/s")
		}
	}
	elapsed := time.Since(start)
	assert.Equal(t, 10, granted)
	assert.GreaterOrEqual(t, elapsed, 900*time.Millisecond, "10 tokens at 5/s with burst 5 need ~1 s")
	assert.Less(t, elapsed, 2*time.Second)

	// Immediately after, the bucket is empty: a non-blocking ask fails.
	require.ErrorIs(t, a.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0), apperr.ErrRateLimitWait)
	avail, err := a.Available(ctx, entity.LaneBulk, entity.ChannelEmail)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, avail, 0.0)
	assert.LessOrEqual(t, avail, 5.0)

	// The hash and its TTL exist.
	ttl, err := client.TTL(ctx, keys.Bucket(entity.LaneBulk, entity.ChannelEmail)).Result()
	require.NoError(t, err)
	assert.Greater(t, ttl, time.Duration(0))

	// in_app is never limited.
	for i := 0; i < 50; i++ {
		require.NoError(t, a.Acquire(ctx, entity.LaneBulk, entity.ChannelInApp, 0))
	}
}

func TestIntegration_TokenBucketFailsClosedWhenRedisGone(t *testing.T) {
	client := newTestClient(t)
	rates := map[entity.Lane]float64{entity.LaneBulk: 8}
	tb := NewTokenBucket(client, NewKeys("x"), clock.System{}, rates, 4, nil, nil)
	ctx := context.Background()
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))

	require.NoError(t, client.Close())
	// Local bucket: 8/4 = 2 tokens burst.
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	require.ErrorIs(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0), apperr.ErrRateLimitWait)
}

func TestIntegration_DailyQuota(t *testing.T) {
	client := newTestClient(t)
	q := NewDailyQuota(client, NewKeys(testRedisConf.KeyPrefix))
	ctx := context.Background()
	day := time.Date(2026, 9, 22, 3, 0, 0, 0, time.UTC)

	n, err := q.Get(ctx, entity.ProviderSES, day)
	require.NoError(t, err)
	assert.EqualValues(t, 0, n)
	for i := 1; i <= 3; i++ {
		n, err = q.Increment(ctx, entity.ProviderSES, day)
		require.NoError(t, err)
		assert.EqualValues(t, i, n)
	}
	n, err = q.Get(ctx, entity.ProviderSES, day)
	require.NoError(t, err)
	assert.EqualValues(t, 3, n)

	ttl, err := client.TTL(ctx, "itest:daily:ses:2026-09-22").Result()
	require.NoError(t, err)
	assert.InDelta(t, 48*time.Hour, ttl, float64(time.Minute))

	// Other provider and other day are separate counters.
	n, _ = q.Get(ctx, entity.ProviderMailpit, day)
	assert.EqualValues(t, 0, n)
	n, _ = q.Get(ctx, entity.ProviderSES, day.Add(24*time.Hour))
	assert.EqualValues(t, 0, n)

	require.NoError(t, client.Close())
	_, err = q.Increment(ctx, entity.ProviderSES, day)
	require.Error(t, err, "increment reports the outage")
	n, err = q.Get(ctx, entity.ProviderSES, day)
	require.Error(t, err)
	assert.EqualValues(t, 0, n)
}

func TestIntegration_FrequencyCapCountsAndFailsOpen(t *testing.T) {
	client := newTestClient(t)
	fc := NewFrequencyCap(client, NewKeys(testRedisConf.KeyPrefix))
	ctx := context.Background()
	user := uuid.New()
	other := uuid.New()

	for i := 1; i <= 3; i++ {
		n, ok, err := fc.Increment(ctx, user, "2026-09-22")
		require.NoError(t, err)
		assert.True(t, ok)
		assert.EqualValues(t, i, n)
	}
	n, ok, err := fc.Increment(ctx, user, "2026-09-23")
	require.NoError(t, err)
	assert.True(t, ok)
	assert.EqualValues(t, 1, n, "a new local day starts at 1")
	_, _, err = fc.Increment(ctx, other, "2026-09-22")
	require.NoError(t, err)

	ttl, err := client.TTL(ctx, "itest:freq:"+user.String()+":2026-09-22").Result()
	require.NoError(t, err)
	assert.InDelta(t, 48*time.Hour, ttl, float64(time.Minute))

	// Forget drops every date of that user and nothing else.
	require.NoError(t, fc.Forget(ctx, user))
	keys, err := client.Keys(ctx, "itest:freq:*").Result()
	require.NoError(t, err)
	require.Len(t, keys, 1)
	assert.True(t, strings.Contains(keys[0], other.String()))

	// Fail-open: closed client → ok=false with the error, count 0.
	require.NoError(t, client.Close())
	n, ok, err = fc.Increment(ctx, user, "2026-09-22")
	assert.Error(t, err)
	assert.False(t, ok)
	assert.EqualValues(t, 0, n)
}

func TestIntegration_UnreadCounterNeverNegative(t *testing.T) {
	client := newTestClient(t)
	u := NewUnreadCounter(client, NewKeys(testRedisConf.KeyPrefix))
	ctx := context.Background()
	user := uuid.New()

	_, err := u.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)

	// Add on an absent key stays absent (caller recomputes).
	require.NoError(t, u.Add(ctx, user, 1))
	_, err = u.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)

	require.NoError(t, u.Set(ctx, user, 2))
	require.NoError(t, u.Add(ctx, user, 3))
	n, err := u.Get(ctx, user)
	require.NoError(t, err)
	assert.EqualValues(t, 5, n)

	require.NoError(t, u.Add(ctx, user, -10))
	n, err = u.Get(ctx, user)
	require.NoError(t, err)
	assert.EqualValues(t, 0, n, "clamped at zero")

	require.NoError(t, u.Set(ctx, user, -4))
	n, _ = u.Get(ctx, user)
	assert.EqualValues(t, 0, n)

	require.NoError(t, u.Delete(ctx, user))
	_, err = u.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)
}

func TestIntegration_RetryBudgetRatio(t *testing.T) {
	client := newTestClient(t)
	clk := clock.NewFake(time.Date(2026, 9, 22, 10, 0, 30, 0, time.UTC))
	m := telemetry.New()
	rb := NewRetryBudget(client, NewKeys(testRedisConf.KeyPrefix), clk, m, nil)
	ctx := context.Background()

	ratio, err := rb.Ratio(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.Equal(t, 0.0, ratio)

	for i := 0; i < 8; i++ {
		require.NoError(t, rb.Record(ctx, entity.LaneBulk, false))
	}
	for i := 0; i < 2; i++ {
		require.NoError(t, rb.Record(ctx, entity.LaneBulk, true))
	}
	ratio, err = rb.Ratio(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.InDelta(t, 0.2, ratio, 1e-9)

	// The next minute still sees the previous one; two minutes on it is gone.
	clk.Advance(time.Minute)
	require.NoError(t, rb.Record(ctx, entity.LaneBulk, true))
	ratio, err = rb.Ratio(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.InDelta(t, 3.0/11.0, ratio, 1e-9)

	clk.Advance(time.Minute)
	ratio, err = rb.Ratio(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.InDelta(t, 1.0, ratio, 1e-9, "only the last minute's single retry remains")

	// Other lanes are independent.
	ratio, _ = rb.Ratio(ctx, entity.LaneCritical)
	assert.Equal(t, 0.0, ratio)

	minute := clk.Now().Unix() / 60
	ttl, err := client.TTL(ctx, "itest:retrywin:bulk:total:"+strconv.FormatInt(minute-1, 10)).Result()
	require.NoError(t, err)
	assert.Greater(t, ttl, time.Duration(0))
	assert.LessOrEqual(t, ttl, 3*time.Minute)

	// Advisory: a dead Redis yields ratio 0 and no error.
	require.NoError(t, client.Close())
	ratio, err = rb.Ratio(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.Equal(t, 0.0, ratio)
	assert.Error(t, rb.Record(ctx, entity.LaneBulk, true))
}

func TestIntegration_ContactCache(t *testing.T) {
	client := newTestClient(t)
	cc := NewContactCache(client, NewKeys(testRedisConf.KeyPrefix))
	ctx := context.Background()
	user := uuid.New()

	_, err := cc.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)

	contact := &entity.Contact{UserID: user, Email: pii.MustEmail("Khach@Example.com"), EmailVerified: true, Locale: "vi"}
	require.Error(t, cc.Set(ctx, user, contact, 0), "no TTL is refused")
	require.NoError(t, cc.Set(ctx, user, contact, 15*time.Minute))

	got, err := cc.Get(ctx, user)
	require.NoError(t, err)
	assert.Equal(t, user, got.UserID)
	assert.Equal(t, "Khach@Example.com", got.Email.Raw())
	assert.True(t, got.EmailVerified)
	assert.Equal(t, "vi", got.Locale)

	raw, err := client.Get(ctx, "itest:contact:"+user.String()).Result()
	require.NoError(t, err)
	assert.Contains(t, raw, `"email":"Khach@Example.com"`, "raw address stored, not the redacted Stringer")
	assert.Contains(t, raw, `"email_verified":true`)
	ttl, err := client.TTL(ctx, "itest:contact:"+user.String()).Result()
	require.NoError(t, err)
	assert.InDelta(t, 15*time.Minute, ttl, float64(5*time.Second))

	// Corrupt entry → miss and dropped.
	require.NoError(t, client.Set(ctx, "itest:contact:"+user.String(), "{not json", time.Minute).Err())
	_, err = cc.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)
	exists, _ := client.Exists(ctx, "itest:contact:"+user.String()).Result()
	assert.EqualValues(t, 0, exists)

	require.NoError(t, cc.Set(ctx, user, contact, time.Minute))
	require.NoError(t, cc.Delete(ctx, user))
	_, err = cc.Get(ctx, user)
	require.ErrorIs(t, err, apperr.ErrCacheMiss)
}

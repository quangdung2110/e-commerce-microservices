package redis

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// unreachableClient points at a port nobody listens on so every command
// fails fast: this is the "Redis down" fixture for fail-closed tests.
func unreachableClient(t *testing.T) *redis.Client {
	t.Helper()
	c := redis.NewClient(&redis.Options{
		Addr:        "127.0.0.1:1",
		DialTimeout: 50 * time.Millisecond,
		ReadTimeout: 50 * time.Millisecond,
		MaxRetries:  -1,
	})
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func TestKeys(t *testing.T) {
	id := uuid.MustParse("11111111-2222-3333-4444-555555555555")
	k := NewKeys("notification")
	assert.Equal(t, "notification:contact:11111111-2222-3333-4444-555555555555", k.Contact(id))
	assert.Equal(t, "notification:freq:11111111-2222-3333-4444-555555555555:2026-09-22", k.FreqCap(id, "2026-09-22"))
	assert.Equal(t, "notification:freq:11111111-2222-3333-4444-555555555555:*", k.FreqCapPattern(id))
	assert.Equal(t, "notification:inbox:unread:11111111-2222-3333-4444-555555555555", k.Unread(id))
	assert.Equal(t, "notification:bucket:bulk:email", k.Bucket(entity.LaneBulk, entity.ChannelEmail))
	day := time.Date(2026, 9, 22, 23, 30, 0, 0, time.FixedZone("ICT", 7*3600))
	assert.Equal(t, "notification:daily:ses:2026-09-22", k.Daily(entity.ProviderSES, day), "UTC date, not local")
	assert.Equal(t, "notification:retrywin:critical:retry:1234", k.RetryWindow(entity.LaneCritical, "retry", 1234))
	assert.Equal(t, "bucket:bulk:email", NewKeys("").Bucket(entity.LaneBulk, entity.ChannelEmail))
}

func TestTokenBucket_InAppAndUnlimitedLanesNeverWait(t *testing.T) {
	tb := NewTokenBucket(unreachableClient(t), NewKeys("t"), clock.NewFake(time.Unix(1000, 0)),
		map[entity.Lane]float64{entity.LaneBulk: 0}, 4, nil, nil)
	require.NoError(t, tb.Acquire(context.Background(), entity.LaneBulk, entity.ChannelInApp, 0))
	require.NoError(t, tb.Acquire(context.Background(), entity.LaneBulk, entity.ChannelEmail, 0), "rate 0 = unlimited")
	require.NoError(t, tb.Acquire(context.Background(), entity.LaneCritical, entity.ChannelEmail, 0), "missing lane = unlimited")
}

func TestTokenBucket_FailsClosedToLocalBucket(t *testing.T) {
	clk := clock.NewFake(time.Unix(1000, 0))
	m := telemetry.New()
	// 8 tokens/s shared, 4 replicas → 2 tokens/s locally, burst 2.
	tb := NewTokenBucket(unreachableClient(t), NewKeys("t"), clk,
		map[entity.Lane]float64{entity.LaneBulk: 8}, 4, m, nil)
	ctx := context.Background()

	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	err := tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0)
	require.ErrorIs(t, err, apperr.ErrRateLimitWait, "local burst of 2 exhausted")

	// Half a second later one token (2/s × 0.5 s) has refilled.
	clk.Advance(500 * time.Millisecond)
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	require.ErrorIs(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0), apperr.ErrRateLimitWait)

	// Other lanes have their own local bucket.
	tb.rates[entity.LaneCritical] = 4
	require.NoError(t, tb.Acquire(ctx, entity.LaneCritical, entity.ChannelEmail, 0))
}

func TestTokenBucket_ContextCancelledWhileWaiting(t *testing.T) {
	tb := NewTokenBucket(unreachableClient(t), NewKeys("t"), clock.System{},
		map[entity.Lane]float64{entity.LaneBulk: 1}, 1, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	go func() {
		time.Sleep(30 * time.Millisecond)
		cancel()
	}()
	err := tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 5*time.Second)
	require.ErrorIs(t, err, context.Canceled)
}

func TestTokenBucket_WaitElapsesWithRealClock(t *testing.T) {
	tb := NewTokenBucket(unreachableClient(t), NewKeys("t"), clock.System{},
		map[entity.Lane]float64{entity.LaneBulk: 1}, 1, nil, nil)
	ctx := context.Background()
	require.NoError(t, tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 0))
	start := time.Now()
	err := tb.Acquire(ctx, entity.LaneBulk, entity.ChannelEmail, 120*time.Millisecond)
	require.ErrorIs(t, err, apperr.ErrRateLimitWait)
	assert.GreaterOrEqual(t, time.Since(start), 100*time.Millisecond)
	assert.Less(t, time.Since(start), 800*time.Millisecond)
}

func TestParseBucketResult(t *testing.T) {
	granted, tokens, err := parseBucketResult([]any{int64(1), "3.5"})
	require.NoError(t, err)
	assert.True(t, granted)
	assert.Equal(t, 3.5, tokens)

	_, _, err = parseBucketResult([]any{int64(1)})
	assert.ErrorIs(t, err, errUnexpectedScriptResult)
	_, _, err = parseBucketResult([]any{"1", "x"})
	assert.ErrorIs(t, err, errUnexpectedScriptResult)
}

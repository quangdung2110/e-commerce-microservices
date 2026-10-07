package redis

import (
	"context"
	"math"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var _ port.RateLimiter = (*TokenBucket)(nil)

// maxPoll bounds the sleep between two attempts so a token released by another
// replica is picked up quickly (06-backpressure.md §6.2 budgets 5 s per token).
const maxPoll = 50 * time.Millisecond

// tokenBucketScript is the shared bucket of 06-backpressure.md §4.2. One Lua
// call refills by elapsed time, takes one token when available and stores
// {tokens, ts} in a hash. The caller supplies its clock (microseconds) so the
// script stays deterministic; a lagging replica clock simply refills nothing.
//
// KEYS[1] bucket key; ARGV[1] rate tokens/s; ARGV[2] burst capacity;
// ARGV[3] now µs; ARGV[4] key TTL seconds.
// Returns {granted 0|1, tokens-left as string} (a Lua number would be
// truncated to an integer on the way back).
var tokenBucketScript = redis.NewScript(`
local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
	tokens = burst
	ts = now
end
local elapsed = now - ts
if elapsed < 0 then
	elapsed = 0
end
tokens = math.min(burst, tokens + elapsed * rate / 1000000)
local granted = 0
if tokens >= 1 then
	tokens = tokens - 1
	granted = 1
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
redis.call('EXPIRE', KEYS[1], ARGV[4])
return {granted, tostring(tokens)}
`)

// TokenBucket is the distributed rate limiter per lane×channel. Only the email
// channel is limited: in_app writes to our own Postgres and has no provider
// quota, so Acquire returns immediately for it.
//
// Fail-closed: when Redis is unreachable each replica falls back to a local
// bucket at rate / limiter_fallback_replicas, so the sum across replicas can
// never exceed the provider quota (06 §4.2). The fallback is per process and
// resets when Redis answers again.
type TokenBucket struct {
	client           *redis.Client
	keys             Keys
	clock            clock.Clock
	rates            map[entity.Lane]float64
	fallbackReplicas int
	metrics          *telemetry.Metrics
	logger           *zap.Logger

	mu       sync.Mutex
	local    map[entity.Lane]*localBucket
	lastWarn atomic.Int64 // unix seconds of the last fallback warning
}

// EmailRatesFromConfig maps lanes.<lane>.email_quota_per_second to the limiter
// input (06 §4.1 table).
func EmailRatesFromConfig(cfg config.LanesConfig) map[entity.Lane]float64 {
	return map[entity.Lane]float64{
		entity.LaneCritical:      cfg.Critical.EmailQuotaPerSecond,
		entity.LaneTransactional: cfg.Transactional.EmailQuotaPerSecond,
		entity.LaneBulk:          cfg.Bulk.EmailQuotaPerSecond,
	}
}

// NewTokenBucket wires the limiter. rates is tokens/s per lane for the email
// channel (0 or missing = unlimited). fallbackReplicas < 1 is treated as 1.
// metrics and logger may be nil.
func NewTokenBucket(client *redis.Client, keys Keys, clk clock.Clock, rates map[entity.Lane]float64, fallbackReplicas int, metrics *telemetry.Metrics, logger *zap.Logger) *TokenBucket {
	if fallbackReplicas < 1 {
		fallbackReplicas = 1
	}
	if logger == nil {
		logger = zap.NewNop()
	}
	if clk == nil {
		clk = clock.System{}
	}
	return &TokenBucket{
		client:           client,
		keys:             keys,
		clock:            clk,
		rates:            rates,
		fallbackReplicas: fallbackReplicas,
		metrics:          metrics,
		logger:           logger,
		local:            map[entity.Lane]*localBucket{},
	}
}

// burstFor is the bucket capacity: one second of quota, at least one token so
// a lane with a fractional rate can still send.
func burstFor(rate float64) float64 { return math.Max(rate, 1) }

// Acquire blocks up to wait for one token. It returns nil when granted,
// errors.ErrRateLimitWait when wait elapsed without a token, or ctx.Err() when
// the caller's context ended first (the worker then releases the row).
func (b *TokenBucket) Acquire(ctx context.Context, lane entity.Lane, channel entity.Channel, wait time.Duration) error {
	if channel != entity.ChannelEmail {
		return nil
	}
	rate := b.rates[lane]
	if rate <= 0 {
		return nil
	}
	if wait < 0 {
		wait = 0
	}

	start := b.clock.Now()
	deadline := start.Add(wait)
	timer := time.NewTimer(0)
	if !timer.Stop() {
		<-timer.C
	}
	defer timer.Stop()

	for {
		granted, tokens := b.try(ctx, lane, channel, rate)
		b.observeTokens(lane, channel, tokens)
		if granted {
			b.observeWait(lane, channel, b.clock.Now().Sub(start))
			return nil
		}

		now := b.clock.Now()
		remaining := deadline.Sub(now)
		if remaining <= 0 {
			b.observeWait(lane, channel, now.Sub(start))
			return apperr.ErrRateLimitWait
		}
		// Sleep until the next token is due, never more than maxPoll so a
		// token freed elsewhere is noticed, never past the deadline.
		sleep := time.Duration((1 - tokens) / rate * float64(time.Second))
		if sleep <= 0 || sleep > maxPoll {
			sleep = maxPoll
		}
		if sleep > remaining {
			sleep = remaining
		}
		timer.Reset(sleep)
		select {
		case <-ctx.Done():
			b.observeWait(lane, channel, b.clock.Now().Sub(start))
			return ctx.Err()
		case <-timer.C:
		}
	}
}

// try makes one attempt against Redis, falling back to the local bucket on
// any Redis error. It returns the grant and the tokens left afterwards.
func (b *TokenBucket) try(ctx context.Context, lane entity.Lane, channel entity.Channel, rate float64) (bool, float64) {
	now := b.clock.Now()
	burst := burstFor(rate)
	ttl := int64(math.Ceil(burst/rate)*2) + 1
	res, err := tokenBucketScript.Run(ctx, b.client, []string{b.keys.Bucket(lane, channel)},
		strconv.FormatFloat(rate, 'f', -1, 64),
		strconv.FormatFloat(burst, 'f', -1, 64),
		now.UnixMicro(),
		ttl,
	).Slice()
	if err == nil {
		granted, tokens, perr := parseBucketResult(res)
		if perr == nil {
			return granted, tokens
		}
		err = perr
	}
	if ctx.Err() != nil {
		// The caller's context ended, not Redis; do not consume a local token.
		return false, 0
	}
	b.warnFallback(err)
	return b.localBucket(lane, rate).take(now)
}

func parseBucketResult(res []any) (bool, float64, error) {
	if len(res) != 2 {
		return false, 0, errUnexpectedScriptResult
	}
	granted, ok := res[0].(int64)
	if !ok {
		return false, 0, errUnexpectedScriptResult
	}
	raw, ok := res[1].(string)
	if !ok {
		return false, 0, errUnexpectedScriptResult
	}
	tokens, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return false, 0, errUnexpectedScriptResult
	}
	return granted == 1, tokens, nil
}

// Available reports the tokens currently in the shared bucket (best effort,
// for the gauge). It does not consume anything.
func (b *TokenBucket) Available(ctx context.Context, lane entity.Lane, channel entity.Channel) (float64, error) {
	if channel != entity.ChannelEmail {
		return math.Inf(1), nil
	}
	rate := b.rates[lane]
	if rate <= 0 {
		return math.Inf(1), nil
	}
	burst := burstFor(rate)
	vals, err := b.client.HMGet(ctx, b.keys.Bucket(lane, channel), "tokens", "ts").Result()
	if err != nil {
		return 0, err
	}
	if len(vals) != 2 || vals[0] == nil || vals[1] == nil {
		return burst, nil // never used yet: full
	}
	tokens, err1 := strconv.ParseFloat(vals[0].(string), 64)
	ts, err2 := strconv.ParseInt(vals[1].(string), 10, 64)
	if err1 != nil || err2 != nil {
		return 0, errUnexpectedScriptResult
	}
	elapsed := float64(b.clock.Now().UnixMicro()-ts) / 1e6
	if elapsed < 0 {
		elapsed = 0
	}
	tokens = math.Min(burst, tokens+elapsed*rate)
	b.observeTokens(lane, channel, tokens)
	return tokens, nil
}

// warnFallback logs the Redis failure at most once per minute; the limiter
// keeps working (locally) so a flood of identical lines helps nobody.
func (b *TokenBucket) warnFallback(err error) {
	now := b.clock.Now().Unix()
	last := b.lastWarn.Load()
	if now-last < 60 {
		return
	}
	if b.lastWarn.CompareAndSwap(last, now) {
		b.logger.Warn("rate limiter: redis unavailable, using local fallback bucket",
			zap.Error(err), zap.Int("fallback_replicas", b.fallbackReplicas))
	}
}

func (b *TokenBucket) localBucket(lane entity.Lane, rate float64) *localBucket {
	b.mu.Lock()
	defer b.mu.Unlock()
	lb, ok := b.local[lane]
	if !ok {
		localRate := rate / float64(b.fallbackReplicas)
		lb = &localBucket{rate: localRate, burst: burstFor(localRate), tokens: burstFor(localRate), ts: b.clock.Now()}
		b.local[lane] = lb
	}
	return lb
}

func (b *TokenBucket) observeWait(lane entity.Lane, channel entity.Channel, d time.Duration) {
	if b.metrics != nil {
		b.metrics.RateLimiterWait.WithLabelValues(string(lane), string(channel)).Observe(d.Seconds())
	}
}

func (b *TokenBucket) observeTokens(lane entity.Lane, channel entity.Channel, tokens float64) {
	if b.metrics != nil {
		b.metrics.RateLimiterTokens.WithLabelValues(string(lane), string(channel)).Set(tokens)
	}
}

// localBucket is the per-process fallback used while Redis is down.
type localBucket struct {
	mu     sync.Mutex
	rate   float64
	burst  float64
	tokens float64
	ts     time.Time
}

func (l *localBucket) take(now time.Time) (bool, float64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	elapsed := now.Sub(l.ts).Seconds()
	if elapsed > 0 {
		l.tokens = math.Min(l.burst, l.tokens+elapsed*l.rate)
		l.ts = now
	}
	if l.tokens >= 1 {
		l.tokens--
		return true, l.tokens
	}
	return false, l.tokens
}

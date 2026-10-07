package redis

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/port"
)

var _ port.RateLimiter = (*RateLimiter)(nil)

// fixedWindowScript increments the counter and, on the first hit, arms the
// window. Counting and expiry must be one atomic step or a burst could reset the
// window forever and defeat the limit.
var fixedWindowScript = redis.NewScript(`
local current = redis.call('INCR', KEYS[1])
if current == 1 then
	redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
	redis.call('PEXPIRE', KEYS[1], ARGV[1])
	ttl = tonumber(ARGV[1])
end
return {current, ttl}
`)

// RateLimiter is a fixed-window counter in Redis.
type RateLimiter struct {
	store
}

// NewRateLimiter wires the rate limiter.
func NewRateLimiter(client *redis.Client, keys *keybuilder.Builder) *RateLimiter {
	return &RateLimiter{store{client: client, keys: keys}}
}

// Allow counts one request against the rule. A Redis failure is reported rather
// than swallowed: the limiter is the only thing standing between one caller and
// the whole address book API, so it fails closed even though the address cache
// next to it degrades.
func (s *RateLimiter) Allow(ctx context.Context, scope, subject string, rule port.RateLimitRule) (port.RateLimitResult, error) {
	if !rule.Enabled() {
		return port.RateLimitResult{Allowed: true}, nil
	}

	key := s.keys.RateLimit(scope, subject)
	raw, err := fixedWindowScript.Run(ctx, s.client, []string{key}, rule.Window.Milliseconds()).Slice()
	if err != nil {
		return port.RateLimitResult{}, mapError(err)
	}
	if len(raw) != 2 {
		return port.RateLimitResult{}, mapError(errUnexpectedScriptResult)
	}

	current, _ := raw[0].(int64)
	ttlMillis, _ := raw[1].(int64)

	remaining := rule.Limit - int(current)
	if remaining < 0 {
		remaining = 0
	}
	retryAfter := time.Duration(ttlMillis) * time.Millisecond
	if retryAfter <= 0 {
		retryAfter = rule.Window
	}

	return port.RateLimitResult{
		Allowed:    current <= int64(rule.Limit),
		Remaining:  remaining,
		RetryAfter: retryAfter,
	}, nil
}

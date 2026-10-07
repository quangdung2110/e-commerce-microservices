// Package redis implements the volatile, TTL-bound state of the Notification
// Service (09-data-model.md §2, "Không có bảng cho"): the distributed token
// bucket per lane×channel, the provider daily counter, the bulk frequency cap,
// the inbox unread counter, the retry-budget window and the 15-minute contact
// cache.
//
// Failure policy differs per adapter and is stated on each type: the rate
// limiter fails closed to a local safe rate (06-backpressure.md §4.2), the
// frequency cap and retry budget fail open (08-decide.md §6, 06 §6.1), the
// counters report the error so the caller decides.
package redis

import (
	"context"
	"fmt"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/notification-service/internal/common/config"
)

// NewClient builds the Redis client shared by every adapter. It does not
// dial: call Ping at startup so a misconfigured address fails fast, while a
// transient outage at boot still lets the process come up (the limiter and the
// cap have their own fallbacks).
func NewClient(cfg config.RedisConfig) *redis.Client {
	return redis.NewClient(&redis.Options{
		Addr:         cfg.Addr(),
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     cfg.PoolSize,
		MinIdleConns: cfg.MinIdleConns,
		DialTimeout:  cfg.DialTimeout,
		ReadTimeout:  cfg.ReadTimeout,
		WriteTimeout: cfg.WriteTimeout,
	})
}

// Ping verifies connectivity (readiness probe, startup check).
func Ping(ctx context.Context, client *redis.Client) error {
	if err := client.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("redis: ping: %w", err)
	}
	return nil
}

// errUnexpectedScriptResult means a Lua script returned a shape the adapter
// does not understand, which is a bug rather than an outage.
var errUnexpectedScriptResult = fmt.Errorf("redis: unexpected script result")

// incrWithTTLScript increments a counter and arms its TTL on the first hit.
// Counting and expiry must be one atomic step, otherwise a crash between INCR
// and EXPIRE leaves a counter that never expires. A key found without TTL
// (for example after a RESTORE) is re-armed as well.
var incrWithTTLScript = redis.NewScript(`
local current = redis.call('INCR', KEYS[1])
if current == 1 or redis.call('TTL', KEYS[1]) < 0 then
	redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
`)

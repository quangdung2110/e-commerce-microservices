package redis

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.DailyQuota = (*DailyQuota)(nil)

// dailyTTL keeps yesterday's counter readable for dashboards, then drops it.
const dailyTTL = 48 * time.Hour

// DailyQuota is the account-level 24 h send counter of 06-backpressure.md
// §4.1: every lane increments the same key so bulk can stop at 80 % of
// Max24HourSend and leave the rest for critical/transactional.
//
// Redis errors are returned as-is: the worker decides (a counter miss must not
// block a critical send, but bulk should treat "unknown" as "stop").
type DailyQuota struct {
	client *redis.Client
	keys   Keys
}

// NewDailyQuota wires the counter.
func NewDailyQuota(client *redis.Client, keys Keys) *DailyQuota {
	return &DailyQuota{client: client, keys: keys}
}

// Increment adds one send for provider on the UTC day of day and returns the
// new total. The TTL is armed atomically with the first increment.
func (q *DailyQuota) Increment(ctx context.Context, provider entity.Provider, day time.Time) (int64, error) {
	n, err := incrWithTTLScript.Run(ctx, q.client, []string{q.keys.Daily(provider, day)}, int64(dailyTTL.Seconds())).Int64()
	if err != nil {
		return 0, fmt.Errorf("redis: daily quota increment: %w", err)
	}
	return n, nil
}

// Get returns the current total (0 when no send happened yet).
func (q *DailyQuota) Get(ctx context.Context, provider entity.Provider, day time.Time) (int64, error) {
	n, err := q.client.Get(ctx, q.keys.Daily(provider, day)).Int64()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return 0, nil
		}
		return 0, fmt.Errorf("redis: daily quota get: %w", err)
	}
	return n, nil
}

package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.FrequencyCap = (*FrequencyCap)(nil)

// freqCapTTL: a local day plus slack for timezones far from UTC (08-decide §6).
const freqCapTTL = 48 * time.Hour

// forgetScanCount bounds each SCAN page; Forget runs only on user.deleted so
// the cost of a full keyspace scan is acceptable there and nowhere else.
const forgetScanCount = 100

// FrequencyCap counts bulk sends per user per local day at Decide
// (08-decide.md §6). It fails open: on a Redis error Increment reports
// ok=false so Decide lets the bulk through and raises frequency_cap_degraded
// (blocking every bulk because Redis is down would be an overreaction).
type FrequencyCap struct {
	client *redis.Client
	keys   Keys
}

// NewFrequencyCap wires the cap.
func NewFrequencyCap(client *redis.Client, keys Keys) *FrequencyCap {
	return &FrequencyCap{client: client, keys: keys}
}

// Increment counts one bulk for the user on localDate (YYYY-MM-DD in the
// user's timezone) and returns the count after increment. ok=false with the
// error means Redis did not answer; the count is then meaningless.
func (f *FrequencyCap) Increment(ctx context.Context, userID uuid.UUID, localDate string) (int64, bool, error) {
	n, err := incrWithTTLScript.Run(ctx, f.client, []string{f.keys.FreqCap(userID, localDate)}, int64(freqCapTTL.Seconds())).Int64()
	if err != nil {
		return 0, false, fmt.Errorf("redis: frequency cap increment: %w", err)
	}
	return n, true, nil
}

// Forget removes every frequency-cap key of the user (11-privacy.md §2:
// user.deleted → DEL). The date part is unknown, so this is the one place a
// SCAN is used; it is rare and the pattern is narrow.
func (f *FrequencyCap) Forget(ctx context.Context, userID uuid.UUID) error {
	pattern := f.keys.FreqCapPattern(userID)
	iter := f.client.Scan(ctx, 0, pattern, forgetScanCount).Iterator()
	var batch []string
	for iter.Next(ctx) {
		batch = append(batch, iter.Val())
		if len(batch) >= forgetScanCount {
			if err := f.client.Del(ctx, batch...).Err(); err != nil {
				return fmt.Errorf("redis: frequency cap forget: %w", err)
			}
			batch = batch[:0]
		}
	}
	if err := iter.Err(); err != nil {
		return fmt.Errorf("redis: frequency cap scan: %w", err)
	}
	if len(batch) > 0 {
		if err := f.client.Del(ctx, batch...).Err(); err != nil {
			return fmt.Errorf("redis: frequency cap forget: %w", err)
		}
	}
	return nil
}

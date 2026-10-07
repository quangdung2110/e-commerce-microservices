package redis

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var _ port.RetryBudget = (*RetryBudget)(nil)

const (
	retryKindRetry = "retry"
	retryKindTotal = "total"
	// retryWindowTTL keeps the current and previous minute plus slack.
	retryWindowTTL = 3 * time.Minute
)

// RetryBudget tracks retries versus all sends per lane in 1-minute buckets
// (06-backpressure.md §6.1). Ratio reads the current and the previous minute
// so the window never collapses to a few samples right after a minute rolls.
//
// The budget is advisory: it only changes how far a failed row is pushed, so
// a Redis error yields ratio 0 (no deferral) instead of blocking anything.
type RetryBudget struct {
	client  *redis.Client
	keys    Keys
	clock   clock.Clock
	metrics *telemetry.Metrics
	logger  *zap.Logger
}

// NewRetryBudget wires the budget; metrics and logger may be nil.
func NewRetryBudget(client *redis.Client, keys Keys, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *RetryBudget {
	if clk == nil {
		clk = clock.System{}
	}
	if logger == nil {
		logger = zap.NewNop()
	}
	return &RetryBudget{client: client, keys: keys, clock: clk, metrics: metrics, logger: logger}
}

func (r *RetryBudget) minute() int64 { return r.clock.Now().Unix() / 60 }

// Record counts one send attempt; isRetry marks attempts beyond the first.
func (r *RetryBudget) Record(ctx context.Context, lane entity.Lane, isRetry bool) error {
	minute := r.minute()
	// MULTI/EXEC keeps INCR and EXPIRE together; re-arming the TTL on every
	// hit is harmless for keys that live three minutes.
	pipe := r.client.TxPipeline()
	totalKey := r.keys.RetryWindow(lane, retryKindTotal, minute)
	pipe.Incr(ctx, totalKey)
	pipe.Expire(ctx, totalKey, retryWindowTTL)
	if isRetry {
		retryKey := r.keys.RetryWindow(lane, retryKindRetry, minute)
		pipe.Incr(ctx, retryKey)
		pipe.Expire(ctx, retryKey, retryWindowTTL)
	}
	if _, err := pipe.Exec(ctx); err != nil {
		return fmt.Errorf("redis: retry budget record: %w", err)
	}
	return nil
}

// Ratio returns retries / total over the current and previous minute, 0 when
// nothing was sent or Redis is unavailable.
func (r *RetryBudget) Ratio(ctx context.Context, lane entity.Lane) (float64, error) {
	minute := r.minute()
	keys := []string{
		r.keys.RetryWindow(lane, retryKindRetry, minute),
		r.keys.RetryWindow(lane, retryKindRetry, minute-1),
		r.keys.RetryWindow(lane, retryKindTotal, minute),
		r.keys.RetryWindow(lane, retryKindTotal, minute-1),
	}
	vals, err := r.client.MGet(ctx, keys...).Result()
	if err != nil {
		r.logger.Debug("retry budget: redis unavailable, assuming ratio 0", zap.String("lane", string(lane)), zap.Error(err))
		return 0, nil
	}
	if len(vals) != 4 {
		return 0, nil
	}
	retry := asInt(vals[0]) + asInt(vals[1])
	total := asInt(vals[2]) + asInt(vals[3])
	ratio := 0.0
	if total > 0 {
		ratio = float64(retry) / float64(total)
	}
	if r.metrics != nil {
		r.metrics.RetryBudgetRatio.WithLabelValues(string(lane)).Set(ratio)
	}
	return ratio, nil
}

func asInt(v any) int64 {
	s, ok := v.(string)
	if !ok {
		return 0
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0
	}
	return n
}

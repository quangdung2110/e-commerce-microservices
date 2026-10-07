package auth

import (
	"context"
	"math"
	"sync"
	"time"
)

// limiter is a small local token bucket: the per-lane client-side limit of
// 06-backpressure.md §3 that keeps a bulk drain from flooding Auth. It is
// per process on purpose (the Redis bucket is for the provider quota); with
// R replicas Auth sees at most R × rate.
type limiter struct {
	mu     sync.Mutex
	rate   float64 // tokens per second; <= 0 means unlimited
	burst  float64
	tokens float64
	last   time.Time
	now    func() time.Time
	sleep  func(ctx context.Context, d time.Duration) error
}

func newLimiter(ratePerSecond float64, now func() time.Time) *limiter {
	burst := math.Max(1, math.Floor(ratePerSecond))
	return &limiter{
		rate:   ratePerSecond,
		burst:  burst,
		tokens: burst,
		now:    now,
		sleep:  sleepCtx,
	}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// wait blocks until a token is available or ctx ends.
func (l *limiter) wait(ctx context.Context) error {
	if l == nil || l.rate <= 0 {
		return nil
	}
	for {
		l.mu.Lock()
		now := l.now()
		if l.last.IsZero() {
			l.last = now
		}
		elapsed := now.Sub(l.last).Seconds()
		if elapsed > 0 {
			l.tokens = math.Min(l.burst, l.tokens+elapsed*l.rate)
			l.last = now
		}
		if l.tokens >= 1 {
			l.tokens--
			l.mu.Unlock()
			return nil
		}
		need := (1 - l.tokens) / l.rate
		l.mu.Unlock()
		if err := l.sleep(ctx, time.Duration(need*float64(time.Second))); err != nil {
			return err
		}
	}
}

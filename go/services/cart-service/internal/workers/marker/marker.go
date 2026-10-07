// Package marker holds the abandoned-marker loop (WF-CART-005).
//
// Redis keyspace notifications are deliberately not used to detect
// abandonment: they are fire-and-forget and are dropped on disconnect, which
// would silently lose the only event Cart publishes.
package marker

import (
	"context"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
)

// Config tunes the marker loop.
type Config struct {
	// Interval is the pause between full passes over all 64 buckets.
	Interval time.Duration
	// BatchSize caps how many overdue carts one bucket yields per pass.
	BatchSize int
	// OperationTimeout bounds a single Redis call.
	OperationTimeout time.Duration
}

// Worker scans the 64 abandonment indexes and marks overdue carts.
type Worker struct {
	store   port.CartStore
	cfg     Config
	metrics *observability.Metrics
	logger  *zap.Logger
	now     func() time.Time
}

// New builds the marker worker.
func New(store port.CartStore, cfg Config, metrics *observability.Metrics, logger *zap.Logger) *Worker {
	if cfg.Interval <= 0 {
		cfg.Interval = time.Minute
	}
	if cfg.BatchSize <= 0 {
		cfg.BatchSize = 200
	}
	if cfg.OperationTimeout <= 0 {
		cfg.OperationTimeout = 5 * time.Second
	}
	return &Worker{store: store, cfg: cfg, metrics: metrics, logger: logger, now: time.Now}
}

// Run scans until the context is cancelled.
func (w *Worker) Run(ctx context.Context) error {
	w.logger.Info("abandoned marker started",
		zap.Duration("interval", w.cfg.Interval),
		zap.Int("buckets", domain.BucketCount),
	)
	defer w.logger.Info("abandoned marker stopped")

	ticker := time.NewTicker(w.cfg.Interval)
	defer ticker.Stop()

	for {
		w.pass(ctx)
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

// pass walks every bucket once, round-robin. A single deployment runs one
// active marker; there is no distributed lease, so scaling means splitting
// bucket ownership rather than adding markers over the same buckets.
func (w *Worker) pass(ctx context.Context) {
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		if ctx.Err() != nil {
			return
		}
		w.scanBucket(ctx, bucket)
	}
}

func (w *Worker) scanBucket(ctx context.Context, bucket int) {
	scanCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	now := w.now()
	due, err := w.store.DueCarts(scanCtx, bucket, now, w.cfg.BatchSize)
	cancel()
	if err != nil {
		w.metrics.MarkerError(bucket)
		w.logger.Warn("failed to scan abandonment index",
			zap.Int("bucket", bucket),
			zap.Error(err),
		)
		return
	}
	if len(due) == 0 {
		return
	}

	// The oldest overdue deadline in the bucket is the marker's lag signal.
	w.metrics.MarkerOverdueAge(bucket, now.Sub(time.UnixMilli(due[0].Deadline)))

	for _, cart := range due {
		if ctx.Err() != nil {
			return
		}
		w.markOne(ctx, bucket, cart)
	}
}

func (w *Worker) markOne(ctx context.Context, bucket int, cart port.DueCart) {
	markCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	defer cancel()

	result, err := w.store.MarkAbandoned(markCtx, port.MarkAbandonedCommand{
		Identity:         domain.IdentityFromToken(bucket, cart.UserToken),
		ExpectedDeadline: cart.Deadline,
	})
	if err != nil {
		w.metrics.MarkerError(bucket)
		w.logger.Warn("failed to mark cart abandoned",
			zap.Int("bucket", bucket),
			zap.String("cart_id", domain.CartIDFromToken(cart.UserToken)),
			zap.Error(err),
		)
		return
	}

	if result.Marked {
		w.metrics.MarkerMarked(bucket)
		w.logger.Info("cart marked abandoned",
			zap.Int("bucket", bucket),
			zap.String("cart_id", domain.CartIDFromToken(cart.UserToken)),
			zap.Int64("abandonment_cycle", result.AbandonmentCycle),
			zap.String("stream_id", result.StreamID),
		)
		return
	}

	// A refused transition is normal, not an error: the user wrote just before
	// the marker read the deadline, or the cart expired first. Neither case
	// emits an event.
	w.metrics.MarkerSkipped(bucket, result.Reason)
	if result.Reason == "STALE_DEADLINE" {
		return
	}
	w.logger.Debug("skipped abandoned transition",
		zap.Int("bucket", bucket),
		zap.String("reason", result.Reason),
	)
}

// Package trimmer reclaims outbox stream memory (WF-CART-007).
//
// XACK marks an entry delivered but does not free it, so without this loop the
// 64 outbox streams grow without bound.
package trimmer

import (
	"context"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
)

// Config tunes the trimmer loop.
type Config struct {
	// Interval is the pause between full passes over all 64 buckets.
	Interval time.Duration
	// OperationTimeout bounds a single Redis call.
	OperationTimeout time.Duration
}

// Worker trims one bucket stream at a time, round-robin.
type Worker struct {
	outbox  port.OutboxStore
	cfg     Config
	metrics *observability.Metrics
	logger  *zap.Logger
}

// New builds the trimmer worker.
func New(outbox port.OutboxStore, cfg Config, metrics *observability.Metrics, logger *zap.Logger) *Worker {
	if cfg.Interval <= 0 {
		cfg.Interval = 5 * time.Minute
	}
	if cfg.OperationTimeout <= 0 {
		cfg.OperationTimeout = 5 * time.Second
	}
	return &Worker{outbox: outbox, cfg: cfg, metrics: metrics, logger: logger}
}

// Run trims until the context is cancelled.
func (w *Worker) Run(ctx context.Context) error {
	w.logger.Info("stream trimmer started",
		zap.Duration("interval", w.cfg.Interval),
		zap.Int("buckets", domain.BucketCount),
	)
	defer w.logger.Info("stream trimmer stopped")

	ticker := time.NewTicker(w.cfg.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			w.pass(ctx)
		}
	}
}

func (w *Worker) pass(ctx context.Context) {
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		if ctx.Err() != nil {
			return
		}
		w.trimBucket(ctx, bucket)
	}
}

// trimBucket trims one stream if it is safe to do so.
//
// Trimming is skipped entirely when the stream or the consumer group does not
// exist yet, and the watermark always keeps the oldest pending entry, so an
// entry the relay has not yet acked is never removed.
func (w *Worker) trimBucket(ctx context.Context, bucket int) {
	opCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	defer cancel()

	exists, err := w.outbox.StreamExists(opCtx, bucket)
	if err != nil {
		w.metrics.TrimError(bucket)
		w.logger.Warn("failed to probe outbox stream", zap.Int("bucket", bucket), zap.Error(err))
		return
	}
	if !exists {
		return
	}

	minID, safe, err := w.outbox.TrimWatermark(opCtx, bucket)
	if err != nil {
		w.metrics.TrimError(bucket)
		w.logger.Warn("failed to compute trim watermark", zap.Int("bucket", bucket), zap.Error(err))
		return
	}
	if !safe {
		return
	}

	removed, err := w.outbox.Trim(opCtx, bucket, minID)
	if err != nil {
		w.metrics.TrimError(bucket)
		w.logger.Warn("failed to trim outbox stream", zap.Int("bucket", bucket), zap.Error(err))
		return
	}
	if removed == 0 {
		return
	}

	w.metrics.Trimmed(bucket, removed)
	w.logger.Info("trimmed outbox stream",
		zap.Int("bucket", bucket),
		zap.String("min_id", minID),
		zap.Int64("removed", removed),
	)
}

// Package purge deletes the non-partitioned ledgers in batches: the three
// dedup ledgers (30/30/14 days), published outbox rows (7 days) and ops
// idempotency records (24 h) — 09-data-model.md §9, 16-operations.md §4.
// Batches keep each DELETE short so the hot tables never see a long lock.
package purge

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Table labels for purge_rows_deleted_total.
const (
	TableIngestDedup        = "ingest_dedup"
	TableDeliveryDedup      = "delivery_dedup"
	TableProviderEventDedup = "provider_event_dedup"
	TableOutbox             = "outbox"
	TableOpsIdempotency     = "ops_idempotency"
)

// maxBatchesPerTable bounds one pass so a huge backlog is spread over
// several intervals instead of one very long run.
const maxBatchesPerTable = 1000

// Worker is the purge job.
type Worker struct {
	dedup   port.DedupRepository
	outbox  port.OutboxRepository
	ops     port.OpsRepository
	cfg     config.JobsConfig
	idemTTL time.Duration
	clock   clock.Clock
	metrics *telemetry.Metrics
	logger  *zap.Logger
}

// New wires the job. idempotencyTTL is cfg.Extensions.IdempotencyTTL (falls
// back to retention_days.ops_idempotency when zero); metrics may be nil.
func New(dedup port.DedupRepository, outbox port.OutboxRepository, ops port.OpsRepository,
	cfg config.JobsConfig, idempotencyTTL time.Duration, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Worker {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Worker{
		dedup: dedup, outbox: outbox, ops: ops, cfg: cfg, idemTTL: idempotencyTTL,
		clock: clk, metrics: metrics, logger: logger.With(zap.String("worker", "purge")),
	}
}

// Run executes one pass immediately and then every purge_interval.
func (w *Worker) Run(ctx context.Context) {
	interval := w.cfg.PurgeInterval
	if interval <= 0 {
		interval = time.Hour
	}
	w.RunOnce(ctx)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.RunOnce(ctx)
		}
	}
}

// RunOnce purges every ledger once, looping per table while a batch came
// back full.
func (w *Worker) RunOnce(ctx context.Context) {
	now := w.clock.Now()
	r := w.cfg.RetentionDays

	w.purgeBatched(ctx, TableIngestDedup, days(now, r.IngestDedup), w.dedup.PurgeIngest)
	w.purgeBatched(ctx, TableDeliveryDedup, days(now, r.DeliveryDedup), w.dedup.PurgeDelivery)
	w.purgeBatched(ctx, TableProviderEventDedup, days(now, r.ProviderEventDedup), w.dedup.PurgeProviderEvent)
	w.purgeBatched(ctx, TableOutbox, days(now, r.Outbox), w.outbox.PurgePublished)

	idemBefore := now.Add(-w.idemTTL)
	if w.idemTTL <= 0 {
		idemBefore = days(now, r.OpsIdempotency)
	}
	n, err := w.ops.PurgeIdempotent(ctx, idemBefore)
	w.record(TableOpsIdempotency, n, err)
}

type purgeFunc func(ctx context.Context, before time.Time, limit int) (int64, error)

func (w *Worker) purgeBatched(ctx context.Context, table string, before time.Time, purge purgeFunc) {
	limit := w.cfg.PurgeBatch
	if limit <= 0 {
		limit = 10000
	}
	var total int64
	for i := 0; i < maxBatchesPerTable; i++ {
		if ctx.Err() != nil {
			return
		}
		n, err := purge(ctx, before, limit)
		if err != nil {
			w.record(table, total, err)
			return
		}
		total += n
		if n < int64(limit) {
			break
		}
	}
	w.record(table, total, nil)
}

func (w *Worker) record(table string, deleted int64, err error) {
	if w.metrics != nil && deleted > 0 {
		w.metrics.PurgeRowsDeleted.WithLabelValues(table).Add(float64(deleted))
	}
	if err != nil {
		w.logger.Error("purge failed", zap.String("table", table), zap.Int64("deleted", deleted), zap.Error(err))
		return
	}
	if deleted > 0 {
		w.logger.Info("purge completed", zap.String("table", table), zap.Int64("deleted", deleted))
	}
}

func days(now time.Time, n int) time.Time {
	if n <= 0 {
		// Zero retention would delete everything; treat it as "not configured"
		// and keep one day so a misconfiguration cannot wipe a live ledger.
		n = 1
	}
	return now.Add(-time.Duration(n) * 24 * time.Hour)
}

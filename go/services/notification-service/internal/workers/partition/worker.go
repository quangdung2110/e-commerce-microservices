// Package partition keeps the monthly partitions ahead of time and drops the
// ones past retention (09-data-model.md §9, 16-operations.md §4). A missing
// partition makes every INSERT fail, so the job runs once at start and then
// on every jobs.partition_interval, and exports partition_future_months so
// the alert "< 1 month ahead" can fire before that happens.
package partition

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Table names as the maintenance functions know them.
const (
	TableRequest       = "notification_request"
	TableDelivery      = "notification_delivery"
	TableProviderEvent = "provider_event"
	TableInbox         = "inbox_item"
)

// Worker is the partition job.
type Worker struct {
	maint   port.MaintenanceRepository
	cfg     config.JobsConfig
	clock   clock.Clock
	metrics *telemetry.Metrics
	logger  *zap.Logger
}

// New wires the job. metrics may be nil in tests.
func New(maint port.MaintenanceRepository, cfg config.JobsConfig, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Worker {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Worker{maint: maint, cfg: cfg, clock: clk, metrics: metrics, logger: logger.With(zap.String("worker", "partition"))}
}

// Run executes one pass immediately and then every partition_interval until
// ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	interval := w.cfg.PartitionInterval
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

// RunOnce performs one maintenance pass: ensure, measure, drop, dead tuples.
// Each step is independent so a failing DROP never blocks the CREATE that
// keeps writes alive.
func (w *Worker) RunOnce(ctx context.Context) {
	now := w.clock.Now()
	ahead := w.cfg.PartitionAheadMonths
	if ahead <= 0 {
		ahead = 2
	}

	if err := w.maint.EnsurePartitions(ctx, now, ahead); err != nil {
		w.logger.Error("ensure partitions failed", zap.Int("ahead_months", ahead), zap.Error(err))
	} else {
		w.logger.Info("partitions ensured", zap.Int("ahead_months", ahead))
	}

	if future, err := w.maint.FutureMonths(ctx, now); err != nil {
		w.logger.Error("partition future months failed", zap.Error(err))
	} else {
		for table, months := range future {
			if w.metrics != nil {
				w.metrics.PartitionFutureMonths.WithLabelValues(table).Set(float64(months))
			}
			if months < 1 {
				w.logger.Error("partition coverage below one month", zap.String("table", table), zap.Int("future_months", months))
			}
		}
	}

	retention := w.Retention()
	dropped, refused, err := w.maint.DropExpiredPartitions(ctx, now, retention)
	if err != nil {
		w.logger.Error("drop expired partitions failed", zap.Error(err))
	}
	for _, name := range dropped {
		w.logger.Info("partition dropped", zap.String("partition", name))
	}
	for _, name := range refused {
		// A delivery partition still holding PENDING/RETRY/SENDING rows is kept
		// (09-data-model.md §9); it will be retried next pass.
		w.logger.Warn("partition drop refused: unfinished rows remain", zap.String("partition", name))
	}

	if ratio, err := w.maint.DeadTupleRatio(ctx, TableDelivery); err != nil {
		w.logger.Warn("dead tuple ratio unavailable", zap.String("table", TableDelivery), zap.Error(err))
	} else if w.metrics != nil {
		w.metrics.TableDeadTupleRatio.WithLabelValues(TableDelivery).Set(ratio)
	}
}

// Retention maps each partitioned table to its retention in days from
// jobs.retention_days.
func (w *Worker) Retention() map[string]int {
	r := w.cfg.RetentionDays
	return map[string]int{
		TableRequest:       r.Request,
		TableDelivery:      r.Delivery,
		TableProviderEvent: r.ProviderEvent,
		TableInbox:         r.Inbox,
	}
}

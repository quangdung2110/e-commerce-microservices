// Package reaper returns rows whose worker died mid-send to the queue,
// reclaims expired per-Order ordering leases and exports the queue gauges
// (02-pipeline.md §5.4, 05-ordering.md §4, 14-observability.md §2.2).
package reaper

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Worker runs the lease reaper and the queue-stats exporter.
type Worker struct {
	deliveries      port.DeliveryRepository
	ordering        port.OrderingRepository
	control         port.LaneControl
	clk             clock.Clock
	metrics         *telemetry.Metrics
	logger          *zap.Logger
	reapInterval    time.Duration
	metricsInterval time.Duration
}

// New wires the worker. ordering, control and metrics may be nil.
func New(deliveries port.DeliveryRepository, ordering port.OrderingRepository, control port.LaneControl, reapInterval, metricsInterval time.Duration, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Worker {
	if logger == nil {
		logger = zap.NewNop()
	}
	if clk == nil {
		clk = clock.System{}
	}
	if reapInterval <= 0 {
		reapInterval = 30 * time.Second
	}
	if metricsInterval <= 0 {
		metricsInterval = 15 * time.Second
	}
	return &Worker{
		deliveries:      deliveries,
		ordering:        ordering,
		control:         control,
		clk:             clk,
		metrics:         metrics,
		logger:          logger.Named("reaper"),
		reapInterval:    reapInterval,
		metricsInterval: metricsInterval,
	}
}

// Run blocks until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	reap := time.NewTicker(w.reapInterval)
	stats := time.NewTicker(w.metricsInterval)
	defer reap.Stop()
	defer stats.Stop()

	w.ReapOnce(ctx)
	w.ExportStats(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-reap.C:
			w.ReapOnce(ctx)
		case <-stats.C:
			w.ExportStats(ctx)
		}
	}
}

// ReapOnce moves SENDING rows past their lease back to RETRY with
// last_error_code = lease_expired; the next claimer applies the lane policy
// (04-idempotency.md lớp C3).
func (w *Worker) ReapOnce(ctx context.Context) int64 {
	n, err := w.deliveries.ReapExpiredLeases(ctx, w.clk.Now())
	if err != nil {
		w.logger.Error("reap expired leases", zap.Error(err))
		return 0
	}
	if n > 0 {
		w.logger.Warn("leases expired, rows returned to RETRY", zap.Int64("rows", n))
		if w.metrics != nil {
			w.metrics.DeliveryLeaseExpired.WithLabelValues("all").Add(float64(n))
		}
	}
	w.reapOrdering(ctx)
	return n
}

// reapOrdering deletes ordering leases past lease_until (compare-and-delete;
// a lease renewed or taken over meanwhile is kept — 16-operations.md R-15).
// The next acquirer takes an expired lease over anyway; this keeps the table
// and the oldest-age gauge honest when no other delivery of the order comes.
func (w *Worker) reapOrdering(ctx context.Context) {
	if w.ordering == nil {
		return
	}
	n, err := w.ordering.ReapExpiredLeases(ctx, w.clk.Now())
	if err != nil {
		w.logger.Error("reap expired ordering leases", zap.Error(err))
		return
	}
	if n > 0 {
		w.logger.Warn("ordering leases expired and reclaimed", zap.Int64("leases", n))
	}
}

// exportOrdering sets ordering_lease_oldest_age_seconds per source (0 when
// nothing expired is waiting to be reclaimed).
func (w *Worker) exportOrdering(ctx context.Context, now time.Time) {
	if w.ordering == nil {
		return
	}
	ages, err := w.ordering.ExpiredLeaseAges(ctx, now)
	if err != nil {
		w.logger.Warn("ordering lease stats", zap.Error(err))
		return
	}
	seen := map[string]bool{entity.SourceOrder: false}
	for _, a := range ages {
		w.metrics.OrderingLeaseOldestAge.WithLabelValues(a.Source).Set(a.Age.Seconds())
		seen[a.Source] = true
	}
	for source, set := range seen {
		if !set {
			w.metrics.OrderingLeaseOldestAge.WithLabelValues(source).Set(0)
		}
	}
}

// ExportStats refreshes delivery_pending_count / _age / scheduled_count and
// lane_paused.
func (w *Worker) ExportStats(ctx context.Context) {
	if w.metrics == nil {
		return
	}
	now := w.clk.Now()
	stats, err := w.deliveries.PendingStats(ctx, now)
	if err != nil {
		w.logger.Warn("pending stats", zap.Error(err))
	} else {
		scheduled := map[entity.Lane]int64{}
		for _, s := range stats {
			lane, ch := string(s.Lane), string(s.Channel)
			w.metrics.DeliveryPendingCount.WithLabelValues(lane, ch).Set(float64(s.ReadyCount))
			w.metrics.DeliveryPendingAge.WithLabelValues(lane, ch).Set(s.OldestReadyAge.Seconds())
			scheduled[s.Lane] += s.ScheduledCount
		}
		for _, lane := range entity.AllLanes {
			w.metrics.DeliveryScheduledCount.WithLabelValues(string(lane)).Set(float64(scheduled[lane]))
		}
	}
	w.exportOrdering(ctx, now)
	if w.control == nil {
		return
	}
	for _, lane := range entity.AllLanes {
		state, err := w.control.LaneState(ctx, lane)
		if err != nil {
			continue
		}
		for _, ch := range entity.AllChannels {
			v := 0.0
			if state.IsPaused(ch) {
				v = 1
			}
			w.metrics.LanePaused.WithLabelValues(string(lane), string(ch)).Set(v)
		}
	}
}

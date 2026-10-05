// Package outbox publishes outbox rows to Kafka when outbox.publisher =
// poller, and exports the outbox gauges in either mode (09-data-model.md §8,
// 04-idempotency.md lớp D: the CloudEvents id was generated at INSERT, so a
// republish after a crash carries the same id).
package outbox

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// PublisherPoller is the outbox.publisher value that enables publishing here;
// PublisherDebezium leaves publishing to CDC.
const (
	PublisherPoller   = "poller"
	PublisherDebezium = "debezium"
)

// errorBackoff paces retries after a failed publish/commit.
const errorBackoff = time.Second

// Worker drains the outbox (poller mode) and refreshes the gauges.
type Worker struct {
	cfg             config.OutboxConfig
	topic           string
	metricsInterval time.Duration
	store           port.Store
	publisher       port.EventPublisher
	clock           clock.Clock
	metrics         *telemetry.Metrics
	log             *zap.Logger
}

// New wires the worker. topic is cfg.Kafka.Outcomes.Topic; metricsInterval is
// cfg.Jobs.MetricsInterval. publisher may be nil in debezium mode.
func New(cfg config.OutboxConfig, topic string, metricsInterval time.Duration, store port.Store, publisher port.EventPublisher, clk clock.Clock, m *telemetry.Metrics, log *zap.Logger) *Worker {
	if clk == nil {
		clk = clock.System{}
	}
	if log == nil {
		log = zap.NewNop()
	}
	if metricsInterval <= 0 {
		metricsInterval = 15 * time.Second
	}
	return &Worker{
		cfg: cfg, topic: topic, metricsInterval: metricsInterval,
		store: store, publisher: publisher, clock: clk, metrics: m,
		log: log.With(zap.String("component", "outbox_worker"), zap.String("publisher", cfg.Publisher)),
	}
}

// PublishEnabled reports whether this process publishes outbox rows.
func (w *Worker) PublishEnabled() bool { return w.cfg.Publisher == PublisherPoller }

func (w *Worker) batch() int {
	if w.cfg.Batch <= 0 {
		return 100
	}
	return w.cfg.Batch
}

func (w *Worker) pollInterval() time.Duration {
	if w.cfg.PollInterval <= 0 {
		return 500 * time.Millisecond
	}
	return w.cfg.PollInterval
}

// Run loops until ctx is cancelled. In poller mode it publishes every
// poll_interval (immediately again after a full batch); in both modes it
// refreshes the gauges every metrics_interval. Returns ctx.Err().
func (w *Worker) Run(ctx context.Context) error {
	if w.PublishEnabled() && w.publisher == nil {
		return fmt.Errorf("outbox: publisher mode requires an EventPublisher")
	}
	w.log.Info("outbox worker started", zap.Bool("publishing", w.PublishEnabled()), zap.String("topic", w.topic))

	w.ExportStats(ctx)
	metricsTicker := time.NewTicker(w.metricsInterval)
	defer metricsTicker.Stop()

	var next <-chan time.Time
	if w.PublishEnabled() {
		next = time.After(0)
	}
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-metricsTicker.C:
			w.ExportStats(ctx)
		case <-next:
			n, err := w.PublishOnce(ctx)
			wait := w.pollInterval()
			switch {
			case err != nil:
				if ctx.Err() != nil {
					return ctx.Err()
				}
				w.log.Error("outbox publish batch failed; will retry", zap.Error(err))
				wait = errorBackoff
			case n >= w.batch():
				wait = 0
			}
			next = time.After(wait)
		}
	}
}

// PublishOnce fetches one locked batch, publishes each row and marks them
// published in the same transaction. A publish failure rolls the batch back;
// the rows are re-fetched on the next tick and republished with the same id
// (downstream dedups on it). It returns how many rows were published.
func (w *Worker) PublishOnce(ctx context.Context) (int, error) {
	published := 0
	err := w.store.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		rows, err := tx.Outbox().FetchUnpublished(ctx, w.batch())
		if err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		ids := make([]uuid.UUID, 0, len(rows))
		for i := range rows {
			ev := &rows[i]
			if err := w.publisher.Publish(ctx, w.topic, ev.AggregateID, ev); err != nil {
				return fmt.Errorf("publish outbox %s (%s): %w", ev.ID, ev.Type, err)
			}
			ids = append(ids, ev.ID)
		}
		if err := tx.Outbox().MarkPublished(ctx, ids, w.clock.Now()); err != nil {
			return err
		}
		published = len(ids)
		return nil
	})
	if err != nil {
		return 0, err
	}
	if published > 0 {
		w.log.Debug("outbox rows published", zap.Int("count", published))
	}
	return published, nil
}

// ExportStats refreshes outbox_unpublished_count and outbox_oldest_age_seconds.
func (w *Worker) ExportStats(ctx context.Context) {
	if w.metrics == nil || ctx.Err() != nil {
		return
	}
	count, oldest, err := w.store.Outbox().Stats(ctx, w.clock.Now())
	if err != nil {
		w.log.Debug("outbox stats query failed", zap.Error(err))
		return
	}
	w.metrics.OutboxUnpublished.Set(float64(count))
	w.metrics.OutboxOldestAge.Set(oldest.Seconds())
}

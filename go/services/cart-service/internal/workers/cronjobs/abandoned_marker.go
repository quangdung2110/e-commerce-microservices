package cronjobs

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/event"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/config"
)

// AbandonedMarker scans for inactive carts and marks them as abandoned.
type AbandonedMarker struct {
	cartRepo     port.CartRepository
	outboxWriter port.OutboxWriter
	cfg          config.AbandonedMarkerCfg
	numShards    int
	logger       *zap.Logger
	metrics      *Metrics
	cronJob      cron.Job
	schedule     cron.Schedule
}

// NewAbandonedMarker creates a new abandoned cart marker job.
func NewAbandonedMarker(
	cartRepo port.CartRepository,
	outboxWriter port.OutboxWriter,
	cfg config.AbandonedMarkerCfg,
	numShards int,
	logger *zap.Logger,
) *AbandonedMarker {
	return &AbandonedMarker{
		cartRepo:     cartRepo,
		outboxWriter: outboxWriter,
		cfg:          cfg,
		numShards:    numShards,
		logger:       logger.With(zap.String("job", "abandoned_marker")),
		metrics:      NewMetrics(logger),
	}
}

// Run implements the cron.Job interface.
func (j *AbandonedMarker) Run() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	if err := j.execute(ctx); err != nil {
		j.logger.Error("job run failed", zap.Error(err))
	}
}

// execute performs the abandoned cart marking logic.
func (j *AbandonedMarker) execute(ctx context.Context) error {
	j.logger.Info("abandoned cart marker job started",
		zap.Int("threshold_days", j.cfg.InactiveThresholdDays),
		zap.Int("batch_size", j.cfg.BatchSize),
	)

	processed := 0
	failed := 0

	for {
		carts, err := j.cartRepo.ListCartsForAbandonedMark(
			ctx, j.cfg.InactiveThresholdDays, j.cfg.BatchSize,
		)
		if err != nil {
			return fmt.Errorf("list carts: %w", err)
		}
		if len(carts) == 0 {
			break
		}

		for _, cart := range carts {
			if err := j.markCartAbandoned(ctx, cart); err != nil {
				failed++
				j.logger.Error("failed to mark cart abandoned",
					zap.String("cart_id", cart.CartID()),
					zap.Error(err),
				)
				continue
			}
			processed++
		}
	}

	j.metrics.RecordProcessed(processed)
	j.logger.Info("abandoned cart marker job completed",
		zap.Int("processed", processed),
		zap.Int("failed", failed),
	)
	return nil
}

func (j *AbandonedMarker) markCartAbandoned(ctx context.Context, cart *entity.Cart) error {
	_, err := j.cartRepo.UpdateCartStatus(
		ctx, cart.CartID(), entity.CartStatusAbandoned, "",
	)
	if err != nil {
		return fmt.Errorf("update cart status: %w", err)
	}

	shardID := int(keybuilder.ComputeShardID(cart.UserID(), j.numShards))
	nowMs := fmt.Sprintf("%d", time.Now().UTC().UnixMilli())
	payload := map[string]interface{}{
		"cart_id":      cart.CartID(),
		"user_id":      cart.UserID(),
		"abandoned_at": nowMs,
	}
	payloadJSON, _ := json.Marshal(payload)

	writtenID, err := j.outboxWriter.WriteEvent(
		ctx, shardID, cart.UserID(),
		event.EventTypeCartAbandoned, string(payloadJSON),
	)
	if err != nil {
		return fmt.Errorf("XADD CART_ABANDONED: %w", err)
	}

	if err := j.cartRepo.MarkAbandonedEventPublished(ctx, cart.CartID()); err != nil {
		j.logger.Warn("failed to mark event published",
			zap.String("cart_id", cart.CartID()),
			zap.Error(err),
		)
	}

	j.logger.Info("cart marked abandoned",
		zap.String("cart_id", cart.CartID()),
		zap.String("stream_id", writtenID))

	return nil
}

// Schedule returns the cron schedule for this job.
func (j *AbandonedMarker) Schedule() cron.Schedule {
	return j.schedule
}

// SetSchedule sets the cron schedule.
func (j *AbandonedMarker) SetSchedule(s cron.Schedule) {
	j.schedule = s
}

// Metrics returns the job metrics.
func (j *AbandonedMarker) Metrics() *Metrics {
	return j.metrics
}

// Config returns the job configuration.
func (j *AbandonedMarker) Config() config.AbandonedMarkerCfg {
	return j.cfg
}

package cronjobs

import (
	"context"
	"time"

	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/config"
)

// ArchivalCleaner hard-deletes carts based on archival retention policy.
type ArchivalCleaner struct {
	cartRepo port.CartRepository
	cfg      config.ArchivalCleanerCfg
	logger   *zap.Logger
	metrics  *Metrics
	schedule cron.Schedule
}

// NewArchivalCleaner creates a new archival cleaner job.
func NewArchivalCleaner(
	cartRepo port.CartRepository,
	cfg config.ArchivalCleanerCfg,
	logger *zap.Logger,
) *ArchivalCleaner {
	return &ArchivalCleaner{
		cartRepo: cartRepo,
		cfg:      cfg,
		logger:   logger.With(zap.String("job", "archival_cleaner")),
		metrics:  NewMetrics(logger),
	}
}

// Run implements the cron.Job interface.
func (j *ArchivalCleaner) Run() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	if err := j.execute(ctx); err != nil {
		j.logger.Error("job run failed", zap.Error(err))
	}
}

// execute performs the archival cleaning logic.
func (j *ArchivalCleaner) execute(ctx context.Context) error {
	j.logger.Info("archival cleaner job started",
		zap.Int("deleted_ttl_days", j.cfg.DeletedTTLDays),
		zap.Int("checked_out_ttl_days", j.cfg.CheckedOutTTLDays),
		zap.Int("abandoned_ttl_days", j.cfg.AbandonedTTLDays),
	)

	j.cleanupCarts(ctx, j.cfg.DeletedTTLDays, []entity.CartStatus{
		entity.CartStatusDeleted, entity.CartStatusCheckedOut,
	}, "deleted/checked_out")

	j.cleanupCarts(ctx, j.cfg.AbandonedTTLDays, []entity.CartStatus{
		entity.CartStatusAbandoned,
	}, "abandoned")

	j.logger.Info("archival cleaner job completed")
	return nil
}

func (j *ArchivalCleaner) cleanupCarts(ctx context.Context, ttlDays int, statuses []entity.CartStatus, label string) {
	olderThan := time.Now().UTC().AddDate(0, 0, -ttlDays)
	batchSize := 500

	for {
		cartIDs, err := j.cartRepo.ListCartsForCleanup(ctx, olderThan, statuses, batchSize)
		if err != nil {
			j.logger.Error("list carts for cleanup failed",
				zap.String("type", label),
				zap.Error(err),
			)
			return
		}
		if len(cartIDs) == 0 {
			break
		}

		for _, cartID := range cartIDs {
			if err := j.cartRepo.HardDeleteCart(ctx, cartID); err != nil {
				j.logger.Error("hard delete failed",
					zap.String("cart_id", cartID),
					zap.String("type", label),
					zap.Error(err),
				)
				j.metrics.RecordError()
				continue
			}
			j.metrics.RecordDeleted()
			j.logger.Debug("hard deleted cart",
				zap.String("cart_id", cartID),
				zap.String("type", label),
			)
		}
	}
}

// Schedule returns the cron schedule for this job.
func (j *ArchivalCleaner) Schedule() cron.Schedule {
	return j.schedule
}

// SetSchedule sets the cron schedule.
func (j *ArchivalCleaner) SetSchedule(s cron.Schedule) {
	j.schedule = s
}

// Metrics returns the job metrics.
func (j *ArchivalCleaner) Metrics() *Metrics {
	return j.metrics
}

// Config returns the job configuration.
func (j *ArchivalCleaner) Config() config.ArchivalCleanerCfg {
	return j.cfg
}

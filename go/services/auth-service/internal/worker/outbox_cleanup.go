package worker

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// OutboxCleanupJob handles cleanup of old outbox events
type OutboxCleanupJob struct {
	outboxRepo repository.OutboxRepository
	cfg        config.WorkerTaskConfig
	logger     *zap.Logger
}

// NewOutboxCleanupJob creates a new outbox cleanup job
func NewOutboxCleanupJob(
	outboxRepo repository.OutboxRepository,
	cfg config.WorkerTaskConfig,
	logger *zap.Logger,
) *OutboxCleanupJob {
	return &OutboxCleanupJob{
		outboxRepo: outboxRepo,
		cfg:        cfg,
		logger:     logger,
	}
}

// Run executes the outbox event cleanup job
func (j *OutboxCleanupJob) Run(ctx context.Context) error {
	startTime := time.Now()
	j.logger.Info("Starting outbox event cleanup job")

	// Calculate cutoff date (default: 14 days if not configured)
	retentionDays := j.cfg.RetentionDays
	if retentionDays <= 0 {
		retentionDays = domain.OutboxRetentionDays
	}

	cutoffDate := time.Now().AddDate(0, 0, -retentionDays)
	j.logger.Info("Outbox event cleanup cutoff date",
		zap.Time("cutoff", cutoffDate),
		zap.Int("retention_days", retentionDays))

	// Delete old outbox events (only non-pending ones)
	deletedCount, err := j.outboxRepo.DeleteOldEvents(ctx, cutoffDate)
	if err != nil {
		j.logger.Error("Failed to delete old outbox events", zap.Error(err))
		return fmt.Errorf("failed to delete old outbox events: %w", err)
	}

	duration := time.Since(startTime)
	j.logger.Info("Outbox event cleanup job completed",
		zap.Int64("events_deleted", deletedCount),
		zap.Duration("duration", duration))

	return nil
}

// ShouldRun checks if the job should run based on configuration
func (j *OutboxCleanupJob) ShouldRun() bool {
	return j.cfg.Enabled
}

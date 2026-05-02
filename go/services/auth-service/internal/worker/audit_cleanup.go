package worker

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// AuditCleanupJob handles cleanup of old audit logs
type AuditCleanupJob struct {
	auditRepo repository.AuditRepository
	cfg       config.WorkerTaskConfig
	logger    *zap.Logger
}

// NewAuditCleanupJob creates a new audit cleanup job
func NewAuditCleanupJob(
	auditRepo repository.AuditRepository,
	cfg config.WorkerTaskConfig,
	logger *zap.Logger,
) *AuditCleanupJob {
	return &AuditCleanupJob{
		auditRepo: auditRepo,
		cfg:       cfg,
		logger:    logger,
	}
}

// Run executes the audit log cleanup job
func (j *AuditCleanupJob) Run(ctx context.Context) error {
	startTime := time.Now()
	j.logger.Info("Starting audit log cleanup job")

	// Calculate cutoff date
	cutoffDate := time.Now().AddDate(0, 0, -j.cfg.RetentionDays)
	j.logger.Info("Audit log cleanup cutoff date",
		zap.Time("cutoff", cutoffDate),
		zap.Int("retention_days", j.cfg.RetentionDays))

	// Delete old audit logs
	deletedCount, err := j.auditRepo.DeleteOldLogs(ctx, cutoffDate)
	if err != nil {
		j.logger.Error("Failed to delete old audit logs", zap.Error(err))
		return fmt.Errorf("failed to delete old audit logs: %w", err)
	}

	duration := time.Since(startTime)
	j.logger.Info("Audit log cleanup job completed",
		zap.Int64("logs_deleted", deletedCount),
		zap.Duration("duration", duration))

	return nil
}

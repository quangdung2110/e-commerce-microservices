package worker

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// TokenCleanupJob handles cleanup of expired and revoked refresh tokens
type TokenCleanupJob struct {
	sessionRepo repository.SessionRepository
	resetRepo  repository.PasswordResetTokenRepository
	cfg        config.WorkerTaskConfig
	logger     *zap.Logger
}

// NewTokenCleanupJob creates a new token cleanup job
func NewTokenCleanupJob(
	sessionRepo repository.SessionRepository,
	resetRepo repository.PasswordResetTokenRepository,
	cfg config.WorkerTaskConfig,
	logger *zap.Logger,
) *TokenCleanupJob {
	return &TokenCleanupJob{
		sessionRepo: sessionRepo,
		resetRepo:  resetRepo,
		cfg:        cfg,
		logger:     logger,
	}
}

// Run executes the token cleanup job
func (j *TokenCleanupJob) Run(ctx context.Context) error {
	startTime := time.Now()
	j.logger.Info("Starting token cleanup job")

	j.logger.Info("Token cleanup retention days",
		zap.Int("retention_days", j.cfg.RetentionDays))

	// Delete expired sessions
	if err := j.sessionRepo.DeleteExpired(ctx); err != nil {
		j.logger.Error("Failed to delete expired sessions", zap.Error(err))
		return fmt.Errorf("failed to delete expired sessions: %w", err)
	}

	// Delete expired grace period tokens
	if err := j.sessionRepo.DeleteExpiredGraceTokens(ctx); err != nil {
		j.logger.Error("Failed to delete expired grace tokens", zap.Error(err))
		return fmt.Errorf("failed to delete expired grace tokens: %w", err)
	}

	// Delete expired password reset tokens
	if j.resetRepo != nil {
		if err := j.resetRepo.DeleteExpired(ctx); err != nil {
			j.logger.Error("Failed to delete expired password reset tokens", zap.Error(err))
			return fmt.Errorf("failed to delete expired password reset tokens: %w", err)
		}
	}

	duration := time.Since(startTime)
	j.logger.Info("Token cleanup job completed",
		zap.Duration("duration", duration))

	return nil
}

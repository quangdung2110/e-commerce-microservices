package worker

import (
	"context"
	"fmt"
	"time"

	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/jwks"
)

// Scheduler manages background job scheduling using cron
type Scheduler struct {
	cron             *cron.Cron
	logger           *zap.Logger
	jwksRotator      *jwks.Rotator
	tokenCleaner     *TokenCleanupJob
	auditCleaner     *AuditCleanupJob
	outboxCleaner    *OutboxCleanupJob
	autoUnlockWorker *AutoUnlockWorker
	failedLoginResetWorker *FailedLoginCounterResetWorker
	distributedLock  repository.DistributedLockRepository
	cfg              config.WorkerConfig
	stopCh           chan struct{}
}

// NewScheduler creates a new job scheduler
func NewScheduler(
	jwksRotator *jwks.Rotator,
	tokenCleaner *TokenCleanupJob,
	auditCleaner *AuditCleanupJob,
	outboxCleaner *OutboxCleanupJob,
	autoUnlockWorker *AutoUnlockWorker,
	failedLoginResetWorker *FailedLoginCounterResetWorker,
	distributedLock repository.DistributedLockRepository,
	cfg config.WorkerConfig,
	logger *zap.Logger,
) *Scheduler {
	return &Scheduler{
		cron:            cron.New(cron.WithSeconds()),
		logger:          logger,
		jwksRotator:     jwksRotator,
		tokenCleaner:    tokenCleaner,
		auditCleaner:    auditCleaner,
		outboxCleaner:   outboxCleaner,
		autoUnlockWorker: autoUnlockWorker,
		failedLoginResetWorker: failedLoginResetWorker,
		distributedLock: distributedLock,
		cfg:             cfg,
		stopCh:          make(chan struct{}),
	}
}

// Start starts the scheduler and all configured jobs
func (s *Scheduler) Start(ctx context.Context) error {
	if !s.cfg.Enabled {
		s.logger.Info("Workers disabled in configuration")
		return nil
	}

	s.logger.Info("Starting background job scheduler")

	// Schedule key rotation job
	if s.cfg.KeyRotation.Enabled {
		keyRotationSpec := s.cfg.KeyRotation.Cron
		if keyRotationSpec == "" {
			keyRotationSpec = "0 0 0 1 */3 *" // Default: 1st day of every 3rd month at midnight
		}
		_, err := s.cron.AddFunc(keyRotationSpec, func() {
			s.runWithLock(ctx, "key_rotation", s.runKeyRotation)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule key rotation job: %w", err)
		}
		s.logger.Info("Key rotation job scheduled", zap.String("cron", keyRotationSpec))
	}

	// Schedule token cleanup job
	if s.cfg.TokenCleanup.Enabled {
		tokenCleanupSpec := s.cfg.TokenCleanup.Cron
		if tokenCleanupSpec == "" {
			tokenCleanupSpec = "0 0 2 * * *" // Default: 2 AM daily
		}
		_, err := s.cron.AddFunc(tokenCleanupSpec, func() {
			s.runWithLock(ctx, "token_cleanup", s.runTokenCleanup)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule token cleanup job: %w", err)
		}
		s.logger.Info("Token cleanup job scheduled", zap.String("cron", tokenCleanupSpec))
	}

	// Schedule audit cleanup job
	if s.cfg.AuditCleanup.Enabled {
		auditCleanupSpec := s.cfg.AuditCleanup.Cron
		if auditCleanupSpec == "" {
			auditCleanupSpec = "0 0 3 * * *" // Default: 3 AM daily
		}
		_, err := s.cron.AddFunc(auditCleanupSpec, func() {
			s.runWithLock(ctx, "audit_cleanup", s.runAuditCleanup)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule audit cleanup job: %w", err)
		}
		s.logger.Info("Audit cleanup job scheduled", zap.String("cron", auditCleanupSpec))
	}

	// Schedule outbox cleanup job (part of Module 7)
	if s.cfg.OutboxCleanup.Enabled {
		outboxCleanupSpec := s.cfg.OutboxCleanup.Cron
		if outboxCleanupSpec == "" {
			outboxCleanupSpec = "0 0 4 * * *" // Default: 4 AM daily
		}
		_, err := s.cron.AddFunc(outboxCleanupSpec, func() {
			s.runWithLock(ctx, "outbox_cleanup", s.runOutboxCleanup)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule outbox cleanup job: %w", err)
		}
		s.logger.Info("Outbox cleanup job scheduled", zap.String("cron", outboxCleanupSpec))
	}

	// Schedule auto-unlock job (every 5 minutes)
	if s.cfg.AutoUnlock.Enabled {
		autoUnlockSpec := s.cfg.AutoUnlock.Cron
		if autoUnlockSpec == "" {
			autoUnlockSpec = "*/5 * * * *" // Default: Every 5 minutes
		}
		_, err := s.cron.AddFunc(autoUnlockSpec, func() {
			s.runWithLock(ctx, "auto_unlock", s.runAutoUnlock)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule auto-unlock job: %w", err)
		}
		s.logger.Info("Auto-unlock job scheduled", zap.String("cron", autoUnlockSpec))
	}

	// Schedule failed login counter reset job (every 15 minutes)
	if s.cfg.FailedLoginReset.Enabled {
		failedLoginResetSpec := s.cfg.FailedLoginReset.Cron
		if failedLoginResetSpec == "" {
			failedLoginResetSpec = "*/15 * * * *" // Default: Every 15 minutes
		}
		_, err := s.cron.AddFunc(failedLoginResetSpec, func() {
			s.runWithLock(ctx, "failed_login_reset", s.runFailedLoginReset)
		})
		if err != nil {
			return fmt.Errorf("failed to schedule failed login reset job: %w", err)
		}
		s.logger.Info("Failed login reset job scheduled", zap.String("cron", failedLoginResetSpec))
	}

	// Start cron scheduler
	s.cron.Start()

	// Run immediate check for key rotation on startup
	if s.cfg.KeyRotation.Enabled {
		go func() {
			time.Sleep(5 * time.Second) // Wait for service to fully start
			if err := s.runKeyRotation(); err != nil {
				s.logger.Error("Initial key rotation check failed", zap.Error(err))
			}
		}()
	}

	s.logger.Info("Background job scheduler started",
		zap.Bool("key_rotation_enabled", s.cfg.KeyRotation.Enabled),
		zap.Bool("token_cleanup_enabled", s.cfg.TokenCleanup.Enabled),
		zap.Bool("audit_cleanup_enabled", s.cfg.AuditCleanup.Enabled),
		zap.Bool("outbox_cleanup_enabled", s.cfg.OutboxCleanup.Enabled),
		zap.Bool("auto_unlock_enabled", s.cfg.AutoUnlock.Enabled),
		zap.Bool("failed_login_reset_enabled", s.cfg.FailedLoginReset.Enabled))

	return nil
}

// Stop stops the scheduler gracefully
func (s *Scheduler) Stop(ctx context.Context) error {
	s.logger.Info("Stopping background job scheduler")

	// Stop cron scheduler
	stopCtx := s.cron.Stop()

	select {
	case <-stopCtx.Done():
		s.logger.Info("All jobs stopped gracefully")
	case <-time.After(30 * time.Second):
		s.logger.Warn("Job stop timeout, forcing shutdown")
	}

	close(s.stopCh)
	return nil
}

// runWithLock runs a job with distributed lock to prevent concurrent execution
func (s *Scheduler) runWithLock(ctx context.Context, jobName string, job func() error) {
	lockKey := fmt.Sprintf("worker:%s", jobName)
	lockTTL := 10 * time.Minute

	// Try to acquire distributed lock
	acquired, err := s.distributedLock.Acquire(ctx, lockKey, lockTTL)
	if err != nil {
		s.logger.Error("Failed to acquire lock for job", zap.String("job", jobName), zap.Error(err))
		return
	}

	if !acquired {
		s.logger.Info("Job skipped: lock held by another instance", zap.String("job", jobName))
		return
	}

	defer func() {
		if err := s.distributedLock.Release(ctx, lockKey); err != nil {
			s.logger.Warn("Failed to release lock", zap.String("job", jobName), zap.Error(err))
		}
	}()

	// Execute job
	if err := job(); err != nil {
		s.logger.Error("Job execution failed", zap.String("job", jobName), zap.Error(err))
	} else {
		s.logger.Info("Job completed successfully", zap.String("job", jobName))
	}
}

// runKeyRotation runs the JWKS key rotation job
func (s *Scheduler) runKeyRotation() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	if err := s.jwksRotator.Rotate(ctx); err != nil {
		return fmt.Errorf("key rotation failed: %w", err)
	}

	return nil
}

// runTokenCleanup runs the expired token cleanup job
func (s *Scheduler) runTokenCleanup() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	if s.tokenCleaner != nil {
		if err := s.tokenCleaner.Run(ctx); err != nil {
			return fmt.Errorf("token cleanup failed: %w", err)
		}
	}

	return nil
}

// runAuditCleanup runs the old audit log cleanup job
func (s *Scheduler) runAuditCleanup() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	if s.auditCleaner != nil {
		if err := s.auditCleaner.Run(ctx); err != nil {
			return fmt.Errorf("audit cleanup failed: %w", err)
		}
	}

	return nil
}

// runOutboxCleanup runs the old outbox event cleanup job
func (s *Scheduler) runOutboxCleanup() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	if s.outboxCleaner != nil {
		if err := s.outboxCleaner.Run(ctx); err != nil {
			return fmt.Errorf("outbox cleanup failed: %w", err)
		}
	}

	return nil
}

// runAutoUnlock runs the auto-unlock job
func (s *Scheduler) runAutoUnlock() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	if s.autoUnlockWorker != nil {
		if err := s.autoUnlockWorker.Run(ctx); err != nil {
			return fmt.Errorf("auto-unlock failed: %w", err)
		}
	}

	return nil
}

// runFailedLoginReset runs the failed login counter reset job
func (s *Scheduler) runFailedLoginReset() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	if s.failedLoginResetWorker != nil {
		if err := s.failedLoginResetWorker.Run(ctx); err != nil {
			return fmt.Errorf("failed login reset failed: %w", err)
		}
	}

	return nil
}

// ListJobs returns information about scheduled jobs
func (s *Scheduler) ListJobs() []JobInfo {
	jobs := make([]JobInfo, 0)

	if s.cfg.KeyRotation.Enabled {
		jobs = append(jobs, JobInfo{
			Name:    "key_rotation",
			Enabled: true,
			Cron:    s.cfg.KeyRotation.Cron,
		})
	}

	if s.cfg.TokenCleanup.Enabled {
		jobs = append(jobs, JobInfo{
			Name:    "token_cleanup",
			Enabled: true,
			Cron:    s.cfg.TokenCleanup.Cron,
		})
	}

	if s.cfg.AuditCleanup.Enabled {
		jobs = append(jobs, JobInfo{
			Name:    "audit_cleanup",
			Enabled: true,
			Cron:    s.cfg.AuditCleanup.Cron,
		})
	}

	if s.cfg.OutboxCleanup.Enabled {
		jobs = append(jobs, JobInfo{
			Name:    "outbox_cleanup",
			Enabled: true,
			Cron:    s.cfg.OutboxCleanup.Cron,
		})
	}

	return jobs
}

// JobInfo represents information about a scheduled job
type JobInfo struct {
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
	Cron    string `json:"cron"`
}

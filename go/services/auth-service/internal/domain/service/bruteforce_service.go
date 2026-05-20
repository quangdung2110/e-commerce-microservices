package service

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type BruteForceService struct {
	userRepo       port.UserRepository
	failedLogin    port.FailedLoginCache
	auditService   *AuditService
	eventPublisher port.EventPublisher
	logger         *zap.Logger
}

func NewBruteForceService(
	userRepo port.UserRepository,
	failedLogin port.FailedLoginCache,
	auditService *AuditService,
	eventPublisher port.EventPublisher,
	logger *zap.Logger,
) *BruteForceService {
	return &BruteForceService{
		userRepo:       userRepo,
		failedLogin:    failedLogin,
		auditService:   auditService,
		eventPublisher: eventPublisher,
		logger:         logger.With(zap.String("service", "BruteForceService")),
	}
}

// HandleFailedLogin handles a failed login attempt
// Returns (shouldLockAccount, error)
func (s *BruteForceService) HandleFailedLogin(
	ctx context.Context,
	email string,
	userID string,
	ipAddress string,
	deviceInfo string,
) (bool, error) {
	logger := s.logger.With(
		zap.String("email", maskEmail(email)),
		zap.String("user_id", userID),
		zap.String("ip", ipAddress),
	)

	emailCount, emailShouldLock, err := s.failedLogin.IncrementAndCheck(ctx, email)
	if err != nil {
		logger.Error("Failed to increment email counter", zap.Error(err))
		return false, err
	}

	ipCount, err := s.failedLogin.Increment(ctx, ipAddress)
	if err != nil {
		logger.Error("Failed to increment IP counter", zap.Error(err))
	}

	if s.auditService != nil {
		_ = s.auditService.LogLoginFailed(ctx, email, ipAddress, "", deviceInfo, "INVALID_CREDENTIALS")
	}

	if s.eventPublisher != nil && userID != "" {
		failedLoginEvent := event.NewLoginFailed(userID, email, ipAddress, "", deviceInfo)
		_ = s.eventPublisher.Publish(ctx, failedLoginEvent)
	}

	logger.Warn("Failed login attempt",
		zap.Int("attempt_count", emailCount),
		zap.Int("ip_attempt_count", ipCount),
	)

	if emailShouldLock && userID != "" {
		if err := s.lockAccount(ctx, email, logger); err != nil {
			logger.Error("Failed to lock account", zap.Error(err))
			return false, err
		}
		return true, nil
	}

	if ipCount >= constants.DistributedAttackThreshold {
		logger.Warn("Possible distributed brute-force attack detected",
			zap.String("ip", ipAddress),
			zap.Int("attempt_count", ipCount),
		)
	}

	return false, nil
}

// HandleSuccessfulLogin resets failed login counters
func (s *BruteForceService) HandleSuccessfulLogin(
	ctx context.Context,
	email string,
	ipAddress string,
) error {
	logger := s.logger.With(
		zap.String("email", maskEmail(email)),
		zap.String("ip", ipAddress),
	)

	if err := s.failedLogin.Reset(ctx, email); err != nil {
		logger.Error("Failed to reset email counter", zap.Error(err))
		return err
	}

	if err := s.failedLogin.Reset(ctx, ipAddress); err != nil {
		logger.Error("Failed to reset IP counter", zap.Error(err))
		return err
	}

	logger.Debug("Failed login counters reset")
	return nil
}

// lockAccount locks a user account
func (s *BruteForceService) lockAccount(ctx context.Context, email string, logger *zap.Logger) error {
	user, err := s.userRepo.GetByEmail(ctx, email)
	if err != nil {
		logger.Error("Failed to get user for account lock", zap.Error(err))
		return err
	}

	if err := s.userRepo.LockAccount(ctx, user.ID); err != nil {
		logger.Error("Failed to lock account in database", zap.Error(err))
		return err
	}

	if s.auditService != nil {
		_ = s.auditService.LogAccountLocked(ctx, user.ID, user.Email.String(), "FAILED_LOGIN_ATTEMPTS")
	}

	if s.eventPublisher != nil {
		lockEvent := event.NewUserAccountLocked(user.ID, user.Email.String(), "FAILED_LOGIN_ATTEMPTS")
		_ = s.eventPublisher.Publish(ctx, lockEvent)
	}

	logger.Warn("Account locked due to failed login attempts",
		zap.String("lock_reason", "FAILED_LOGIN_ATTEMPTS"),
		zap.Duration("lock_duration", constants.LockoutDuration),
	)

	return nil
}

// IsAccountLocked checks if an account is currently locked
func (s *BruteForceService) IsAccountLocked(ctx context.Context, email string) (bool, error) {
	return s.failedLogin.IsLocked(ctx, email)
}

// GetRemainingLockTime returns the remaining lock time for an account
func (s *BruteForceService) GetRemainingLockTime(ctx context.Context, email string) (time.Duration, error) {
	return s.failedLogin.GetRemainingTTL(ctx, email)
}

// GetFailedLoginCount returns the number of failed login attempts for an identifier
func (s *BruteForceService) GetFailedLoginCount(ctx context.Context, identifier string) (int, error) {
	return s.failedLogin.GetCount(ctx, identifier)
}

// ResetFailedLogins resets failed login counters for an identifier
func (s *BruteForceService) ResetFailedLogins(ctx context.Context, identifier string) error {
	return s.failedLogin.Reset(ctx, identifier)
}

package password

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

const (
	passwordChangeRateLimit = 7 * 24 * time.Hour // 1 week
)

// ChangeUsecase handles password change for authenticated users
type ChangeUsecase struct {
	sessionRepo      repository.SessionRepository
	refreshTokenRepo repository.RefreshTokenRepository
	rateLimitRepo    repository.RateLimitRepository
	userService      common.UserService
	eventPublisher   common.EventPublisher
	passwordEncoder  common.PasswordEncoder
	logger           *zap.Logger
}

// NewChangeUsecase creates new password change usecase
func NewChangeUsecase(
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	rateLimitRepo repository.RateLimitRepository,
	userService common.UserService,
	eventPublisher common.EventPublisher,
	passwordEncoder common.PasswordEncoder,
	logger *zap.Logger,
) *ChangeUsecase {
	return &ChangeUsecase{
		sessionRepo:      sessionRepo,
		refreshTokenRepo: refreshTokenRepo,
		rateLimitRepo:    rateLimitRepo,
		userService:      userService,
		eventPublisher:   eventPublisher,
		passwordEncoder:  passwordEncoder,
		logger:           logger,
	}
}

// ChangePassword changes password for authenticated user
func (uc *ChangeUsecase) ChangePassword(ctx context.Context, req *dto.ChangePasswordRequest, userID uuid.UUID, currentSessionID *uuid.UUID, reqCtx *common.RequestContext) (*dto.ChangePasswordResponse, error) {
	// Step 1: Check rate limit (1 request per week per user)
	rateKey := userID.String()
	allowed, remaining, err := uc.rateLimitRepo.CheckPasswordResetRateLimit(ctx, rateKey)
	if err != nil {
		uc.logger.Error("Failed to check password change rate limit", zap.Error(err))
		// Continue on error (fail open)
		return nil, err
	} else if !allowed {
		hours := int(remaining.Hours())
		days := hours / 24
		return nil, fmt.Errorf("password change limit exceeded. Please try again in %d days and %d hours.", days, hours%24)
	}

	// Get current user
	user, err := uc.userService.GetUserByID(ctx, userID)
	if err != nil {
		return nil, serr.ErrUserNotFound
	}

	// Verify old password
	valid, err := uc.passwordEncoder.Verify(req.OldPassword, user.PasswordHash)
	if err != nil || !valid {
		return nil, serr.ErrPasswordIncorrect
	}

	// Validate new password
	if err := utils.ValidatePasswordStrength(req.NewPassword); err != nil {
		return nil, err
	}

	// Check if new password is different
	if req.OldPassword == req.NewPassword {
		return nil, serr.ErrPasswordSameAsOld
	}

	// Hash new password
	passwordHash, err := uc.passwordEncoder.Hash(req.NewPassword)
	if err != nil {
		return nil, fmt.Errorf("failed to hash password: %w", err)
	}

	// Update password
	if err := uc.userService.UpdateUserPassword(ctx, userID, passwordHash); err != nil {
		return nil, fmt.Errorf("failed to update password: %w", err)
	}

	// Revoke all OTHER sessions (keep current if specified)
	sessions, err := uc.sessionRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		uc.logger.Error("Failed to find sessions", zap.Error(err))
	}

	for _, session := range sessions {
		// Skip current session if keepCurrentSession is true
		if req.KeepCurrentSession && currentSessionID != nil && session.SessionID == *currentSessionID {
			continue
		}

		// Delete refresh token in Redis
		deviceID := ""
		if session.DeviceID != nil {
			deviceID = *session.DeviceID
		}
		if err := uc.refreshTokenRepo.Delete(ctx, userID, deviceID); err != nil {
			uc.logger.Error("Failed to delete refresh token", zap.Error(err))
		}

		// Revoke session in PostgreSQL
		if err := uc.sessionRepo.RevokeByID(ctx, session.SessionID); err != nil {
			uc.logger.Error("Failed to revoke session", zap.Error(err))
		}
	}

	// Publish event
	if err := uc.publishPasswordChangedEvent(ctx, userID, reqCtx.DeviceInfo.IPAddress); err != nil {
		uc.logger.Error("Failed to publish password changed event", zap.Error(err))
	}

	// Set rate limit (1 week) after successful password change
	if err := uc.rateLimitRepo.SetPasswordResetRateLimit(ctx, rateKey, passwordChangeRateLimit); err != nil {
		uc.logger.Error("Failed to set password change rate limit", zap.Error(err))
	}

	uc.logger.Info("Password changed successfully",
		zap.String("user_id", userID.String()))

	return &dto.ChangePasswordResponse{
		Success: true,
		Message: "Password has been changed successfully",
	}, nil
}

func (uc *ChangeUsecase) publishPasswordChangedEvent(ctx context.Context, userID uuid.UUID, ipAddress string) error {
	event := &common.PasswordChangedEvent{
		UserID:      &userID,
		ChangedAt:   time.Now().UTC(),
		IPAddress:   ipAddress,
		TriggeredBy: "SELF",
	}
	return uc.eventPublisher.PublishPasswordChanged(ctx, event)
}

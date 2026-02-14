package password

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

const (
	resetTokenLength       = 32
	resetTokenTTL          = 1 * time.Hour
	passwordResetRateLimit = 7 * 24 * time.Hour // 1 week
)

// ResetUsecase handles password reset flow
type ResetUsecase struct {
	passwordResetRepo   repository.PasswordResetTokenRepository
	sessionRepo         repository.SessionRepository
	refreshTokenRepo    repository.RefreshTokenRepository
	rateLimitRepo       repository.RateLimitRepository
	userService         common.UserService
	notificationService common.NotificationService
	eventPublisher      common.EventPublisher
	passwordEncoder     common.PasswordEncoder
	logger              *zap.Logger
}

// NewResetUsecase creates new password reset usecase
func NewResetUsecase(
	passwordResetRepo repository.PasswordResetTokenRepository,
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	rateLimitRepo repository.RateLimitRepository,
	userService common.UserService,
	notificationService common.NotificationService,
	eventPublisher common.EventPublisher,
	passwordEncoder common.PasswordEncoder,
	logger *zap.Logger,
) *ResetUsecase {
	return &ResetUsecase{
		passwordResetRepo:   passwordResetRepo,
		sessionRepo:         sessionRepo,
		refreshTokenRepo:    refreshTokenRepo,
		rateLimitRepo:       rateLimitRepo,
		userService:         userService,
		notificationService: notificationService,
		eventPublisher:      eventPublisher,
		passwordEncoder:     passwordEncoder,
		logger:              logger,
	}
}

// ForgotPassword initiates password reset flow
func (uc *ResetUsecase) ForgotPassword(ctx context.Context, req *dto.ForgotPasswordRequest, reqCtx *common.RequestContext) (*dto.ForgotPasswordResponse, error) {
	// Step 1: Check rate limit (1 request per week per email)
	allowed, remaining, err := uc.rateLimitRepo.CheckPasswordResetRateLimit(ctx, req.Email)
	if err != nil {
		uc.logger.Error("Failed to check password reset rate limit", zap.Error(err))
		// Continue on error (fail open)
		return nil, err
	} else if !allowed {
		hours := int(remaining.Hours())
		days := hours / 24
		return &dto.ForgotPasswordResponse{
			Success: false,
			Message: fmt.Sprintf("Password reset request limit exceeded. Please try again in %d days and %d hours.", days, hours%24),
		}, nil
	}

	// Find user by email
	user, err := uc.userService.GetUserByEmail(ctx, req.Email)
	if err != nil {
		// Don't reveal if email exists
		uc.logger.Info("Password reset requested for non-existent email", zap.String("email", req.Email))
		return &dto.ForgotPasswordResponse{
			Success: false,
			Message: "Non-existent email address. Please check and try again.",
		}, nil
	}

	// Check if user is active
	if !user.Active {
		return &dto.ForgotPasswordResponse{
			Success: false,
			Message: "Account has been deactivated. Please contact support.",
		}, nil
	}

	// Delete existing reset tokens (table: password_reset_tokens)
	existingTokens, _ := uc.passwordResetRepo.GetByUserID(ctx, user.UserID)
	for _, token := range existingTokens {
		uc.passwordResetRepo.Delete(ctx, token.TokenHash)
	}

	// Generate reset token
	resetToken, err := utils.GenerateRandomToken(resetTokenLength)
	if err != nil {
		uc.logger.Error("Failed to generate reset token", zap.Error(err))
		return nil, fmt.Errorf("failed to generate reset token: %w", err)
	}

	// Hash token with SHA-256
	tokenHash := utils.SHA256Hash(resetToken)

	// Save to database
	resetRecord := entity.NewPasswordResetToken(user.UserID, tokenHash, reqCtx.DeviceInfo.IPAddress, reqCtx.DeviceInfo.UserAgent, resetTokenTTL)
	if err := uc.passwordResetRepo.Create(ctx, resetRecord); err != nil {
		return nil, fmt.Errorf("failed to save reset token: %w", err)
	}

	// Send email with reset link
	resetURL := fmt.Sprintf("%s/reset-password?token=%s", "", resetToken)
	if err := uc.notificationService.SendPasswordResetEmail(ctx, req.Email, resetURL); err != nil {
		uc.logger.Error("Failed to send password reset email", zap.Error(err))
	}

	// Set rate limit (1 week) after successful token generation
	if err := uc.rateLimitRepo.SetPasswordResetRateLimit(ctx, req.Email, passwordResetRateLimit); err != nil {
		uc.logger.Error("Failed to set password reset rate limit", zap.Error(err))
	}

	// Publish event
	if err := uc.publishPasswordResetRequestedEvent(ctx, user.UserID, reqCtx.DeviceInfo.IPAddress); err != nil {
		uc.logger.Error("Failed to publish event", zap.Error(err))
	}

	uc.logger.Info("Password reset requested",
		zap.String("user_id", user.UserID.String()),
		zap.String("email", req.Email))

	return &dto.ForgotPasswordResponse{
		Success:     true,
		Message:     "A password reset link has been sent",
		EmailSent:   true,
		MaskedEmail: utils.MaskEmail(req.Email),
	}, nil
}

// ResetPassword resets password using token
func (uc *ResetUsecase) ResetPassword(ctx context.Context, req *dto.ResetPasswordRequest, reqCtx *common.RequestContext) (*dto.ResetPasswordResponse, error) {
	// Validate new password
	if err := utils.ValidatePasswordStrength(req.NewPassword); err != nil {
		return nil, err
	}

	// Hash token
	tokenHash := utils.SHA256Hash(req.Token)

	// Find reset record
	resetRecord, err := uc.passwordResetRepo.GetByTokenHash(ctx, tokenHash)
	if err != nil {
		return nil, serr.ErrPasswordResetTokenInvalid
	}

	// Check if already used
	if resetRecord.UsedAt != nil {
		return nil, serr.ErrPasswordResetTokenAlreadyUsed
	}

	// Check if expired
	if time.Now().UTC().After(resetRecord.ExpiresAt) {
		uc.passwordResetRepo.Delete(ctx, resetRecord.TokenHash)
		return nil, serr.ErrPasswordResetTokenExpired
	}

	// Hash new password
	passwordHash, err := uc.passwordEncoder.Hash(req.NewPassword)
	if err != nil {
		return nil, fmt.Errorf("failed to hash password: %w", err)
	}

	// Update password
	if err := uc.userService.UpdateUserPassword(ctx, resetRecord.UserID, passwordHash); err != nil {
		return nil, fmt.Errorf("failed to update password: %w", err)
	}

	// Mark token as used
	resetRecord.MarkAsUsed()
	if err := uc.passwordResetRepo.Update(ctx, resetRecord); err != nil {
		uc.logger.Error("Failed to update reset token", zap.Error(err))
	}

	// Revoke all sessions
	if err := uc.sessionRepo.RevokeAllByUserID(ctx, resetRecord.UserID); err != nil {
		uc.logger.Error("Failed to revoke sessions", zap.Error(err))
	}

	// Delete all refresh tokens
	if err := uc.refreshTokenRepo.DeleteAllByUserID(ctx, resetRecord.UserID); err != nil {
		uc.logger.Error("Failed to delete refresh tokens", zap.Error(err))
	}

	// Publish event
	if err := uc.publishPasswordChangedEvent(ctx, resetRecord.UserID, reqCtx.DeviceInfo.IPAddress); err != nil {
		uc.logger.Error("Failed to publish event", zap.Error(err))
	}

	uc.logger.Info("Password reset successfully",
		zap.String("user_id", resetRecord.UserID.String()))

	return &dto.ResetPasswordResponse{
		Success: true,
		Message: "Password has been reset successfully. Please login with your new password.",
	}, nil
}

// ValidateResetToken validates password reset token
func (uc *ResetUsecase) ValidateResetToken(ctx context.Context, req *dto.ValidateResetTokenRequest) (*dto.ValidateResetTokenResponse, error) {
	tokenHash := utils.SHA256Hash(req.Token)

	resetRecord, err := uc.passwordResetRepo.GetByTokenHash(ctx, tokenHash)
	if err != nil {
		return &dto.ValidateResetTokenResponse{
			Valid:   false,
			Message: "Invalid or expired token",
		}, nil
	}

	// Check if already used
	if resetRecord.UsedAt != nil {
		return &dto.ValidateResetTokenResponse{
			Valid:   false,
			Message: "Token has already been used",
		}, nil
	}

	// Check if expired
	if time.Now().UTC().After(resetRecord.ExpiresAt) {
		uc.passwordResetRepo.Delete(ctx, resetRecord.TokenHash)
		return &dto.ValidateResetTokenResponse{
			Valid:   false,
			Message: "Token has expired",
		}, nil
	}

	return &dto.ValidateResetTokenResponse{
		Valid:     true,
		Message:   "Token is valid",
		ExpiresAt: resetRecord.ExpiresAt,
	}, nil
}

func (uc *ResetUsecase) publishPasswordResetRequestedEvent(ctx context.Context, userID uuid.UUID, ipAddress string) error {
	event := &common.PasswordResetRequestedEvent{
		UserID:    &userID,
		IPAddress: ipAddress,
		Timestamp: time.Now().UTC(),
	}
	return uc.eventPublisher.PublishPasswordResetRequested(ctx, event)
}

func (uc *ResetUsecase) publishPasswordChangedEvent(ctx context.Context, userID uuid.UUID, ipAddress string) error {
	event := &common.PasswordChangedEvent{
		UserID:      &userID,
		ChangedAt:   time.Now().UTC(),
		IPAddress:   ipAddress,
		TriggeredBy: "SELF",
	}
	return uc.eventPublisher.PublishPasswordChanged(ctx, event)
}

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
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
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
	revokeCache        repository.RevokeBeforeCacheRepository
	rateLimitRepo      repository.RateLimitRepository
	userService        common.UserService
	notificationService common.NotificationService
	eventPublisher     common.EventPublisher
	outboxRepo         repository.OutboxRepository
	passwordEncoder    common.PasswordEncoder
	auditService       *authentication.AuditService
	logger             *zap.Logger
}

// NewResetUsecase creates new password reset usecase
func NewResetUsecase(
	passwordResetRepo repository.PasswordResetTokenRepository,
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	revokeCache repository.RevokeBeforeCacheRepository,
	rateLimitRepo repository.RateLimitRepository,
	userService common.UserService,
	notificationService common.NotificationService,
	eventPublisher common.EventPublisher,
	outboxRepo repository.OutboxRepository,
	passwordEncoder common.PasswordEncoder,
	auditService *authentication.AuditService,
	logger *zap.Logger,
) *ResetUsecase {
	return &ResetUsecase{
		passwordResetRepo:   passwordResetRepo,
		sessionRepo:         sessionRepo,
		refreshTokenRepo:    refreshTokenRepo,
		revokeCache:        revokeCache,
		rateLimitRepo:      rateLimitRepo,
		userService:        userService,
		notificationService: notificationService,
		eventPublisher:     eventPublisher,
		outboxRepo:         outboxRepo,
		passwordEncoder:    passwordEncoder,
		auditService:       auditService,
		logger:             logger,
	}
}

// ForgotPassword initiates password reset flow
func (uc *ResetUsecase) ForgotPassword(ctx context.Context, req *dto.ForgotPasswordRequest, reqCtx *common.RequestContext) (*dto.ForgotPasswordResponse, error) {
	// Step 1: Check rate limit (1 request per week per email)
	allowed, remaining, err := uc.rateLimitRepo.CheckPasswordResetRateLimit(ctx, req.Email)
	if err != nil {
		uc.logger.Error("Failed to check password reset rate limit", zap.Error(err))
		// Continue on error (fail open)
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

	// Write audit log for password reset requested
	uc.writeAuditLog(ctx, user.UserID.String(), types.EventTypePasswordResetRequested, types.SecurityLevelInfo, types.LogCategoryAuth,
		reqCtx.DeviceInfo.IPAddress,
		map[string]any{
			"email": utils.MaskEmail(req.Email),
		})

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
// CRITICAL: After reset, all sessions are revoked and revoke_before is set
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
		uc.writeAuditLog(ctx, "unknown", types.EventTypePasswordResetCompleted, types.SecurityLevelWarning, types.LogCategoryAuth,
			reqCtx.DeviceInfo.IPAddress,
			map[string]any{
				"result": "FAILED",
				"reason": "INVALID_TOKEN",
			})
		return nil, serr.ErrPasswordResetTokenInvalid
	}

	// Check if already used
	if resetRecord.UsedAt != nil {
		uc.writeAuditLog(ctx, resetRecord.UserID.String(), types.EventTypePasswordResetCompleted, types.SecurityLevelWarning, types.LogCategoryAuth,
			reqCtx.DeviceInfo.IPAddress,
			map[string]any{
				"result": "FAILED",
				"reason": "TOKEN_ALREADY_USED",
			})
		return nil, serr.ErrPasswordResetTokenAlreadyUsed
	}

	// Check if expired
	if time.Now().UTC().After(resetRecord.ExpiresAt) {
		uc.passwordResetRepo.Delete(ctx, resetRecord.TokenHash)
		uc.writeAuditLog(ctx, resetRecord.UserID.String(), types.EventTypePasswordResetCompleted, types.SecurityLevelWarning, types.LogCategoryAuth,
			reqCtx.DeviceInfo.IPAddress,
			map[string]any{
				"result": "FAILED",
				"reason": "TOKEN_EXPIRED",
			})
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

	// CRITICAL: Revoke all sessions and set revoke_before
	uc.revokeAllSessions(ctx, resetRecord.UserID, reqCtx.DeviceInfo.IPAddress)

	// Write outbox event for password reset completed
	uc.publishOutboxEvent(ctx, resetRecord.UserID, types.EventTypePasswordResetCompleted)

	// Write audit log for successful password reset
	uc.writeAuditLog(ctx, resetRecord.UserID.String(), types.EventTypePasswordResetCompleted, types.SecurityLevelWarning, types.LogCategoryAuth,
		reqCtx.DeviceInfo.IPAddress,
		map[string]any{
			"result": "SUCCESS",
		})

	// Publish event
	if err := uc.publishPasswordChangedEvent(ctx, resetRecord.UserID, reqCtx.DeviceInfo.IPAddress, "RESET_FLOW"); err != nil {
		uc.logger.Error("Failed to publish event", zap.Error(err))
	}

	uc.logger.Info("Password reset successfully",
		zap.String("user_id", resetRecord.UserID.String()))

	return &dto.ResetPasswordResponse{
		Success: true,
		Message: "Password has been reset successfully. Please login with your new password.",
	}, nil
}

// revokeAllSessions revokes all sessions for a user and sets revoke_before
func (uc *ResetUsecase) revokeAllSessions(ctx context.Context, userID uuid.UUID, ipAddress string) {
	// Revoke all sessions
	if err := uc.sessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
		uc.logger.Error("Failed to revoke sessions", zap.Error(err))
	}

	// Delete all refresh tokens
	if err := uc.refreshTokenRepo.DeleteAllByUserID(ctx, userID); err != nil {
		uc.logger.Error("Failed to delete refresh tokens", zap.Error(err))
	}

	// Set revoke_before timestamp
	now := time.Now()
	if err := uc.revokeCache.SetRevokedBefore(ctx, userID.String(), now); err != nil {
		uc.logger.Error("Failed to set revoke_before", zap.Error(err))
	}

	uc.logger.Info("All sessions revoked for user after password reset",
		zap.String("user_id", userID.String()),
		zap.Time("revoked_at", now))
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

// writeAuditLog is a helper to write audit logs
func (uc *ResetUsecase) writeAuditLog(ctx context.Context, actorID string, eventType types.EventType, level types.SecurityLevel, category types.LogCategory, ipAddress string, payload map[string]any) {
	if uc.auditService != nil {
		_ = uc.auditService.Log(ctx, &authentication.AuditEvent{
			ActorID:       actorID,
			ActorType:    types.ActorTypeUser,
			EventType:    eventType,
			IPAddress:    ipAddress,
			SecurityLevel: level,
			LogCategory:  category,
			Payload:     payload,
		})
	}
}

// publishOutboxEvent publishes an event to the outbox table
func (uc *ResetUsecase) publishOutboxEvent(ctx context.Context, userID uuid.UUID, eventType types.EventType) {
	if uc.outboxRepo == nil {
		return
	}

	event := entity.NewOutboxEvent(
		string(eventType),
		"auth-service",
		userID,
		map[string]any{
			"user_id": userID.String(),
		},
	)

	if err := uc.outboxRepo.Create(ctx, event); err != nil {
		uc.logger.Error("Failed to create outbox event", zap.Error(err))
	}
}

func (uc *ResetUsecase) publishPasswordResetRequestedEvent(ctx context.Context, userID uuid.UUID, ipAddress string) error {
	event := &common.PasswordResetRequestedEvent{
		UserID:    &userID,
		IPAddress: ipAddress,
		Timestamp: time.Now().UTC(),
	}
	return uc.eventPublisher.PublishPasswordResetRequested(ctx, event)
}

func (uc *ResetUsecase) publishPasswordChangedEvent(ctx context.Context, userID uuid.UUID, ipAddress string, triggeredBy string) error {
	event := &common.PasswordChangedEvent{
		UserID:      &userID,
		ChangedAt:   time.Now().UTC(),
		IPAddress:   ipAddress,
		TriggeredBy: triggeredBy,
	}
	return uc.eventPublisher.PublishPasswordChanged(ctx, event)
}

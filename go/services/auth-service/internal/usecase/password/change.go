package password

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
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
	revokeCache     repository.RevokeBeforeCacheRepository
	rateLimitRepo    repository.RateLimitRepository
	userService      common.UserService
	eventPublisher   common.EventPublisher
	passwordEncoder  common.PasswordEncoder
	auditService    *authentication.AuditService
	logger          *zap.Logger
}

// NewChangeUsecase creates new password change usecase
func NewChangeUsecase(
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	revokeCache repository.RevokeBeforeCacheRepository,
	rateLimitRepo repository.RateLimitRepository,
	userService common.UserService,
	eventPublisher common.EventPublisher,
	passwordEncoder common.PasswordEncoder,
	auditService *authentication.AuditService,
	logger *zap.Logger,
) *ChangeUsecase {
	return &ChangeUsecase{
		sessionRepo:      sessionRepo,
		refreshTokenRepo: refreshTokenRepo,
		revokeCache:   revokeCache,
		rateLimitRepo:    rateLimitRepo,
		userService:      userService,
		eventPublisher:   eventPublisher,
		passwordEncoder:  passwordEncoder,
		auditService:   auditService,
		logger:          logger,
	}
}

// ChangePassword changes password for authenticated user
// CRITICAL: After password change, all other sessions are revoked and revoke_before is set
func (uc *ChangeUsecase) ChangePassword(ctx context.Context, req *dto.ChangePasswordRequest, userID uuid.UUID, currentSessionID *uuid.UUID, reqCtx *common.RequestContext) (*dto.ChangePasswordResponse, error) {
	// Step 1: Check rate limit (1 request per week per user)
	rateKey := userID.String()
	allowed, remaining, err := uc.rateLimitRepo.CheckPasswordResetRateLimit(ctx, rateKey)
	if err != nil {
		uc.logger.Error("Failed to check password change rate limit", zap.Error(err))
		// Continue on error (fail open)
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
		// Write audit log for failed password change
		uc.writeAuditLog(ctx, userID.String(), types.EventTypePasswordChanged, types.SecurityLevelWarning, types.LogCategoryUser,
			reqCtx.DeviceInfo.IPAddress,
			map[string]any{
				"result":  "FAILED",
				"reason":  "INVALID_PASSWORD",
			})
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

	// Count sessions to revoke for audit
	sessionsToRevoke := int64(0)
	sessions, err := uc.sessionRepo.GetActiveByUserID(ctx, userID)
	if err == nil {
		for _, session := range sessions {
			if !(req.KeepCurrentSession && currentSessionID != nil && session.SessionID == *currentSessionID) {
				sessionsToRevoke++
			}
		}
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

	// CRITICAL STEP: Revoke all OTHER sessions and set revoke_before
	if err := uc.revokeAllOtherSessions(ctx, userID, currentSessionID, req.KeepCurrentSession, reqCtx.DeviceInfo.IPAddress); err != nil {
		uc.logger.Error("Failed to revoke other sessions", zap.Error(err))
	}

	// Publish event
	if err := uc.publishPasswordChangedEvent(ctx, userID, reqCtx.DeviceInfo.IPAddress, "SELF"); err != nil {
		uc.logger.Error("Failed to publish password changed event", zap.Error(err))
	}

	// Write audit log for successful password change
	uc.writeAuditLog(ctx, userID.String(), types.EventTypePasswordChanged, types.SecurityLevelWarning, types.LogCategoryUser,
		reqCtx.DeviceInfo.IPAddress,
		map[string]any{
			"result":            "SUCCESS",
			"sessions_revoked":   sessionsToRevoke,
			"keep_current":       req.KeepCurrentSession,
		})

	// Set rate limit (1 week) after successful password change
	if err := uc.rateLimitRepo.SetPasswordResetRateLimit(ctx, rateKey, passwordChangeRateLimit); err != nil {
		uc.logger.Error("Failed to set password change rate limit", zap.Error(err))
	}

	uc.logger.Info("Password changed successfully",
		zap.String("user_id", userID.String()),
		zap.Int64("sessions_revoked", sessionsToRevoke))

	return &dto.ChangePasswordResponse{
		Success:             true,
		Message:             "Password has been changed successfully. All other sessions have been logged out.",
		SessionsRevokedCount: sessionsToRevoke,
	}, nil
}

// revokeAllOtherSessions revokes all sessions except the current one and sets revoke_before
func (uc *ChangeUsecase) revokeAllOtherSessions(ctx context.Context, userID uuid.UUID, currentSessionID *uuid.UUID, keepCurrent bool, ipAddress string) error {
	sessions, err := uc.sessionRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		return fmt.Errorf("failed to get sessions: %w", err)
	}

	for _, session := range sessions {
		// Skip current session if keepCurrentSession is true
		if keepCurrent && currentSessionID != nil && session.SessionID == *currentSessionID {
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

	// Set revoke_before timestamp to invalidate all existing access tokens at Gateway
	// This ensures that even if an attacker has a valid access token, it will be rejected
	now := time.Now()
	if err := uc.revokeCache.SetRevokedBefore(ctx, userID.String(), now); err != nil {
		return fmt.Errorf("failed to set revoke_before: %w", err)
	}

	uc.logger.Info("Set revoke_before for user",
		zap.String("user_id", userID.String()),
		zap.Time("timestamp", now))

	return nil
}

// writeAuditLog is a helper to write audit logs
func (uc *ChangeUsecase) writeAuditLog(ctx context.Context, actorID string, eventType types.EventType, level types.SecurityLevel, category types.LogCategory, ipAddress string, payload map[string]any) {
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

func (uc *ChangeUsecase) publishPasswordChangedEvent(ctx context.Context, userID uuid.UUID, ipAddress string, triggeredBy string) error {
	event := &common.PasswordChangedEvent{
		UserID:      &userID,
		ChangedAt:   time.Now().UTC(),
		IPAddress:   ipAddress,
		TriggeredBy: triggeredBy,
	}
	return uc.eventPublisher.PublishPasswordChanged(ctx, event)
}

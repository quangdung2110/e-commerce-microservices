package authentication

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
)

// LogoutUsecase handles user logout
type LogoutUsecase struct {
	sessionRepo      repository.SessionRepository
	blacklistRepo    repository.BlacklistRepository
	refreshTokenRepo repository.RefreshTokenRepository
	eventPublisher   common.EventPublisher
	logger           *zap.Logger
}

// NewLogoutUsecase creates new logout usecase
func NewLogoutUsecase(
	sessionRepo repository.SessionRepository,
	blacklistRepo repository.BlacklistRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	eventPublisher common.EventPublisher,
	logger *zap.Logger,
) *LogoutUsecase {
	return &LogoutUsecase{
		sessionRepo:      sessionRepo,
		blacklistRepo:    blacklistRepo,
		refreshTokenRepo: refreshTokenRepo,
		eventPublisher:   eventPublisher,
		logger:           logger,
	}
}

// Execute logs out current session
func (uc *LogoutUsecase) Execute(ctx context.Context, userID uuid.UUID, req *dto.LogoutRequest) error {
	// Step 1: Get session from table: auth_sessions
	session, err := uc.sessionRepo.GetByID(ctx, req.SessionID)
	if err != nil {
		return serr.ErrSessionNotFound
	}

	// Step 2: Verify session belongs to user
	if session.UserID != userID {
		return serr.ErrSessionNotFound
	}

	// Step 3: Blacklist current access token
	jti, ok := ctx.Value("jti").(string)
	tokenExpiry, ok2 := ctx.Value("token_exp").(int64)
	if ok && ok2 {
		remainingTTL := time.Until(time.Unix(tokenExpiry, 0))
		if remainingTTL > 0 {
			if err := uc.blacklistRepo.Add(ctx, types.TokenBlacklist, jti, remainingTTL); err != nil {
				uc.logger.Error("Failed to blacklist access token", zap.Error(err))
			}
		}
	}

	// Step 4: Delete refresh token
	deviceID := ""
	if session.DeviceID != nil {
		deviceID = *session.DeviceID
	}
	// Delete refresh token from Redis (key: refresh:{user_id}:{device_id})
	if err := uc.refreshTokenRepo.Delete(ctx, userID, deviceID); err != nil {
		uc.logger.Error("Failed to delete refresh token", zap.Error(err))
	}

	// Step 5: Revoke session (set is_active = false and revoked_at = now())
	if err := uc.sessionRepo.RevokeByID(ctx, req.SessionID); err != nil {
		return fmt.Errorf("failed to revoke session: %w", err)
	}

	// Step 6: Publish event
	if err := uc.publishLogoutEvent(ctx, userID, req.SessionID); err != nil {
		uc.logger.Error("Failed to publish logout event", zap.Error(err))
	}

	uc.logger.Info("User logged out",
		zap.String("user_id", userID.String()),
		zap.String("session_id", req.SessionID.String()))

	return nil
}

// ExecuteAll logs out from all devices
func (uc *LogoutUsecase) ExecuteAll(ctx context.Context, userID uuid.UUID) error {
	// Step 1: Blacklist user ID to prevent new token issuance
	if err := uc.blacklistRepo.Add(ctx, types.UserBlacklist, userID.String(), 15*time.Minute); err != nil {
		uc.logger.Error("Failed to blacklist user", zap.Error(err))
	}

	// Step 2: Delete all refresh tokens (from Redis, key: refresh:{user_id}:{device_id})
	if err := uc.refreshTokenRepo.DeleteAllByUserID(ctx, userID); err != nil {
		uc.logger.Error("Failed to delete refresh tokens", zap.Error(err))
	}

	// Step 3: Revoke all sessions (set is_active = false and revoked_at = now())
	if err := uc.sessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
		return fmt.Errorf("failed to revoke sessions: %w", err)
	}

	uc.logger.Info("User logged out from all devices",
		zap.String("user_id", userID.String()),
	)

	return nil
}

func (uc *LogoutUsecase) publishLogoutEvent(ctx context.Context, userID, sessionID uuid.UUID) error {
	event := &common.UserLoggedOutEvent{
		UserID:     userID,
		SessionID:  sessionID,
		LogoutType: "MANUAL",
		Timestamp:  time.Now().UTC(),
	}
	return uc.eventPublisher.PublishUserLoggedOut(ctx, event)
}

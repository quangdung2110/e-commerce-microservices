package authentication

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

// RefreshTokenUsecase handles token refresh with Grace Period 10s
// This implements the critical token rotation logic to handle concurrent requests
type RefreshTokenUsecase struct {
	sessionRepo    repository.SessionRepository
	graceCache     repository.GraceCacheRepository
	revokeCache    repository.RevokeBeforeCacheRepository
	blacklistRepo  repository.BlacklistRepository
	eventPublisher common.EventPublisher
	tokenGenerator common.TokenGenerator
	userService    common.UserService
	auditService   *AuditService
	logger         *zap.Logger
}

// NewRefreshTokenUsecase creates a new refresh token usecase with grace period support
func NewRefreshTokenUsecase(
	sessionRepo repository.SessionRepository,
	graceCache repository.GraceCacheRepository,
	revokeCache repository.RevokeBeforeCacheRepository,
	blacklistRepo repository.BlacklistRepository,
	eventPublisher common.EventPublisher,
	tokenGenerator common.TokenGenerator,
	userService common.UserService,
	auditService *AuditService,
	logger *zap.Logger,
) *RefreshTokenUsecase {
	return &RefreshTokenUsecase{
		sessionRepo:    sessionRepo,
		graceCache:     graceCache,
		revokeCache:    revokeCache,
		blacklistRepo:  blacklistRepo,
		eventPublisher: eventPublisher,
		tokenGenerator: tokenGenerator,
		userService:    userService,
		auditService:   auditService,
		logger:         logger,
	}
}

// RefreshTokens executes token refresh with Grace Period 10s
// Quy trình:
//  1. Hash incoming refresh token
//  2. SELECT ... FOR UPDATE trên DB để lock row
//  3. Kiểm tra trạng thái token:
//     a. VALID → tạo token mới, revoke cũ, lưu grace cache Redis TTL 10s
//     b. REVOKED + HIT grace cache → trả tokens mới từ cache (lag tolerant)
//     c. REVOKED + MISS grace cache → TOKEN THEFT → revoke all sessions
func (uc *RefreshTokenUsecase) RefreshTokens(ctx context.Context, refreshToken string, reqCtx *common.RequestContext) (*dto.RefreshTokenResponse, error) {
	// Step 1: Hash the incoming refresh token
	tokenHash := utils.HashToken(refreshToken)

	// Step 2: Query DB with FOR UPDATE to lock the row
	session, err := uc.sessionRepo.FindByTokenHashForUpdate(ctx, tokenHash)
	if err != nil {
		uc.logger.Debug("Session not found for token hash", zap.Error(err))
		return nil, serr.ErrRefreshTokenInvalid
	}

	// Step 3: Check if session is revoked (token already used)
	if session.IsRevoked() {
		return uc.handleRevokedTokenWithGracePeriod(ctx, session, refreshToken)
	}

	// Step 4: Token is valid - perform rotation
	return uc.rotateToken(ctx, session, refreshToken, reqCtx)
}

// rotateToken performs token rotation with grace period
func (uc *RefreshTokenUsecase) rotateToken(ctx context.Context, session *entity.Session, rawToken string, reqCtx *common.RequestContext) (*dto.RefreshTokenResponse, error) {
	// Step 1: Check user status
	user, err := uc.userService.GetUserByID(ctx, session.UserID)
	if err != nil {
		uc.logger.Error("Failed to get user", zap.Error(err), zap.String("user_id", session.UserID.String()))
		return nil, serr.ErrUserNotFound
	}

	if !user.Active {
		return nil, serr.ErrAccountDeactivated
	}

	// Step 2: Generate new access token
	accessToken, err := uc.tokenGenerator.GenerateAccessToken(&entity.TokenClaims{
		JTI:               uuid.New().String(),
		Subject:           session.UserID,
		Role:              user.Role,
		AccountType:       user.AccountType,
		RestrictionStatus: user.RestrictionStatus,
		SessionID:         session.SessionID,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	// Step 3: Generate new refresh token (opaque)
	newRefreshToken, err := uc.tokenGenerator.GenerateRefreshToken()
	if err != nil {
		return nil, fmt.Errorf("failed to generate refresh token: %w", err)
	}

	// Step 4: Create new session record in DB
	newSession := &entity.Session{
		SessionID:        uuid.New(),
		UserID:           session.UserID,
		RefreshTokenHash: utils.HashToken(newRefreshToken),
		FamilyID:         func() *uuid.UUID { id := session.GetFamilyID(); return &id }(), // Keep the same family
		DeviceID:         session.DeviceID,
		DeviceName:       session.DeviceName,
		DeviceType:       session.DeviceType,
		IPAddress:        session.IPAddress,
		UserAgent:        session.UserAgent,
		GeoCountry:       session.GeoCountry,
		GeoCity:          session.GeoCity,
		IsActive:         true,
		ExpiresAt:        time.Now().Add(domain.RefreshTokenTTL),
		CreatedAt:        time.Now(),
		TokenStatus:      types.TokenStatusActive,
	}

	// Create new session with family
	if err := uc.sessionRepo.CreateWithFamily(ctx, newSession, session.GetFamilyID()); err != nil {
		return nil, fmt.Errorf("failed to create new session: %w", err)
	}

	// Step 5: Revoke old token in DB with grace period
	oldTokenHash := utils.HashToken(rawToken)
	graceExpiresAt := time.Now().Add(domain.GracePeriodTTL)
	if err := uc.sessionRepo.RevokeWithGracePeriod(ctx, oldTokenHash, graceExpiresAt); err != nil {
		// Rollback: delete new session
		_ = uc.sessionRepo.RevokeByID(ctx, newSession.SessionID)
		return nil, fmt.Errorf("failed to revoke old token: %w", err)
	}

	// Step 6: Store grace period data in Redis (TTL 10s)
	graceData := types.NewGracePeriodData(accessToken, newRefreshToken, uuid.New().String(), newSession.SessionID.String())
	if err := uc.graceCache.Set(ctx, oldTokenHash, graceData); err != nil {
		uc.logger.Warn("Failed to set grace cache, continuing without it",
			zap.Error(err),
			zap.String("session_id", session.SessionID.String()))
		// Non-fatal: grace period is optimization, not hard requirement
	}

	// Step 7: Write audit log
	if uc.auditService != nil {
		_ = uc.auditService.Log(ctx, &AuditEvent{
			ActorID:        session.UserID.String(),
			ActorType:      types.ActorTypeUser,
			EventType:      types.EventTypeTokenRefreshed,
			IPAddress:      GetClientIP(reqCtx),
			SecurityLevel:  types.SecurityLevelInfo,
			LogCategory:    types.LogCategoryAuth,
			Payload: map[string]any{
				"session_id":     session.SessionID.String(),
				"new_session_id": newSession.SessionID.String(),
				"family_id":      session.GetFamilyID().String(),
			},
		})
	}

	uc.logger.Info("Token refreshed successfully",
		zap.String("user_id", session.UserID.String()),
		zap.String("session_id", session.SessionID.String()),
		zap.String("new_session_id", newSession.SessionID.String()))

	return &dto.RefreshTokenResponse{
		AccessToken:  accessToken,
		RefreshToken: newRefreshToken,
		ExpiresIn:    int64(domain.AccessTokenTTL.Seconds()),
		TokenType:    "Bearer",
	}, nil
}

// handleRevokedTokenWithGracePeriod handles the case when a token has been revoked
// This is the critical logic for detecting token theft
func (uc *RefreshTokenUsecase) handleRevokedTokenWithGracePeriod(ctx context.Context, session *entity.Session, rawToken string) (*dto.RefreshTokenResponse, error) {
	oldTokenHash := utils.HashToken(rawToken)

	// Step 1: Check Grace Period cache
	graceData, err := uc.graceCache.Get(ctx, oldTokenHash)
	if err == nil && graceData != nil {
		// HIT → Client sent request within 10s (lag tolerant)
		uc.logger.Info("Grace period HIT - returning cached tokens",
			zap.String("session_id", session.SessionID.String()),
			zap.String("user_id", session.UserID.String()))

		return &dto.RefreshTokenResponse{
			AccessToken:  graceData.AccessToken,
			RefreshToken: graceData.RefreshToken,
			ExpiresIn:    int64(domain.AccessTokenTTL.Seconds()),
			TokenType:    "Bearer",
		}, nil
	}

	// MISS → Grace period expired → TOKEN THEFT
	uc.logger.Warn("TOKEN THEFT DETECTED",
		zap.String("user_id", session.UserID.String()),
		zap.String("session_id", session.SessionID.String()),
		zap.String("token_hash", oldTokenHash))

	// Step 2: Revoke ALL sessions of user
	if err := uc.sessionRepo.RevokeAllByUserID(ctx, session.UserID); err != nil {
		uc.logger.Error("Failed to revoke all sessions", zap.Error(err))
	}

	// Step 3: Set revoke_before timestamp (invalidate all access tokens at Gateway)
	if err := uc.revokeCache.SetRevokedBefore(ctx, session.UserID.String(), time.Now()); err != nil {
		uc.logger.Error("Failed to set revoked_before", zap.Error(err))
	}

	// Step 4: Publish TOKEN_THEFT_DETECTED event
	if uc.eventPublisher != nil {
		event := &common.SuspiciousActivityEvent{
			UserID:       &session.UserID,
			ActivityType: "TOKEN_THEFT",
			DeviceID:     derefString(session.DeviceID),
			IPAddress:    session.IPAddress,
			Timestamp:    time.Now().UTC(),
		}
		_ = uc.eventPublisher.PublishSuspiciousActivity(ctx, event)
	}

	// Step 5: Write audit log CRITICAL
	if uc.auditService != nil {
		_ = uc.auditService.Log(ctx, &AuditEvent{
			ActorID:        session.UserID.String(),
			ActorType:      types.ActorTypeSystem,
			EventType:      types.EventTypeTokenTheftDetected,
			IPAddress:      session.IPAddress,
			SecurityLevel:  types.SecurityLevelCritical,
			LogCategory:    types.LogCategoryAuth,
			Payload: map[string]any{
				"theft_session_id": session.SessionID.String(),
				"action":           "ALL_TOKENS_REVOKED",
			},
		})
	}

	return nil, serr.ErrTokenTheft
}

// ==================== HELPER FUNCTIONS ====================

// GetClientIP extracts client IP from request context
func GetClientIP(reqCtx *common.RequestContext) string {
	if reqCtx == nil || reqCtx.DeviceInfo.IPAddress == "" {
		return "unknown"
	}
	return reqCtx.DeviceInfo.IPAddress
}

// derefString safely dereferences a string pointer
func derefString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// Execute is the main entry point for the old interface
func (uc *RefreshTokenUsecase) Execute(ctx context.Context, req *dto.RefreshTokenRequest, reqCtx *common.RequestContext) (*dto.RefreshTokenResponse, error) {
	return uc.RefreshTokens(ctx, req.RefreshToken, reqCtx)
}

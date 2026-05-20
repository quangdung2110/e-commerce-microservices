package service

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/svc"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// JWTServicePort defines the interface for JWT operations used by other services
type JWTServicePort interface {
	GenerateAccessToken(ctx context.Context, user *entity.User) (string, error)
}

// AuditServicePort defines the interface for audit operations used by other services
type AuditServicePort interface {
	LogLoginSuccess(ctx context.Context, userID, email, ipAddress, deviceID, deviceInfo string) error
	LogLoginFailed(ctx context.Context, email, ipAddress, deviceID, deviceInfo, reason string) error
	LogLogout(ctx context.Context, userID, ipAddress string) error
	LogForceLogout(ctx context.Context, userID, reason, ipAddress string) error
	LogAccountLocked(ctx context.Context, userID, email, reason string) error
	LogAccountUnlocked(ctx context.Context, userID, email string) error
	LogTokenRefresh(ctx context.Context, userID, ipAddress string) error
	LogTokenTheft(ctx context.Context, userID, tokenID string) error
}

// RefreshService handles refresh token operations with Grace Period
type RefreshService struct {
	userRepo         port.UserRepository
	refreshTokenRepo port.RefreshTokenRepository
	tokenCache       port.TokenCache
	gracePeriodCache port.GracePeriodCache
	jwtService       JWTServicePort
	eventPublisher   port.EventPublisher
	auditService     AuditServicePort
	logger           *zap.Logger
}

func NewRefreshService(
	userRepo port.UserRepository,
	refreshTokenRepo port.RefreshTokenRepository,
	tokenCache port.TokenCache,
	gracePeriodCache port.GracePeriodCache,
	jwtService JWTServicePort,
	eventPublisher port.EventPublisher,
	auditService AuditServicePort,
	logger *zap.Logger,
) *RefreshService {
	return &RefreshService{
		userRepo:         userRepo,
		refreshTokenRepo: refreshTokenRepo,
		tokenCache:       tokenCache,
		gracePeriodCache: gracePeriodCache,
		jwtService:       jwtService,
		eventPublisher:   eventPublisher,
		auditService:     auditService,
		logger:           logger.With(zap.String("service", "RefreshService")),
	}
}

// RefreshTokens handles token refresh with Grace Period
// This implements the concurrency-safe token rotation
func (s *RefreshService) RefreshTokens(
	ctx context.Context,
	oldRefreshToken string,
	oldAccessTokenJTI string,
	oldAccessTokenExp time.Time,
) (*svc.RefreshResponse, error) {
	logger := s.logger

	// Hash the old refresh token
	tokenHash := HashToken(oldRefreshToken)

	// Get the refresh token from database
	storedToken, err := s.refreshTokenRepo.GetByTokenHash(ctx, tokenHash)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return nil, apperrors.ErrInvalidToken()
		}
		logger.Error("Failed to get refresh token", zap.Error(err))
		return nil, fmt.Errorf("failed to get refresh token: %w", err)
	}

	if storedToken == nil {
		return nil, apperrors.ErrInvalidToken()
	}

	// Check if token is already revoked (concurrency case)
	if storedToken.IsRevoked() {
		// Check grace period cache
		cachedAccessToken, cachedRefreshToken, found, err := s.gracePeriodCache.Get(ctx, tokenHash)
		if err != nil {
			logger.Error("Failed to check grace period", zap.Error(err))
		}

		if found {
			// Within 10 second grace period - return cached tokens
			logger.Info("Grace period hit - returning cached tokens")
			return &svc.RefreshResponse{
				AccessToken:     cachedAccessToken,
				NewRefreshToken: cachedRefreshToken,
				ExpiresIn:       int64(constants.AccessTokenTTLDefault.Seconds()),
				TokenType:       "Bearer",
			}, nil
		}

		// Token theft detected - revoke all user tokens
		logger.Warn("Token theft detected", zap.String("user_id", storedToken.UserID))
		if err := s.handleTokenTheft(ctx, storedToken); err != nil {
			logger.Error("Failed to handle token theft", zap.Error(err))
		}
		return nil, apperrors.ErrTokenTheft()
	}

	// Check if token is expired
	if storedToken.IsExpired() {
		logger.Warn("Refresh token expired", zap.String("user_id", storedToken.UserID))
		return nil, apperrors.ErrInvalidToken()
	}

	// Token is valid - proceed with rotation
	return s.rotateTokens(ctx, storedToken, oldAccessTokenJTI, oldAccessTokenExp)
}

// rotateTokens performs token rotation
func (s *RefreshService) rotateTokens(
	ctx context.Context,
	storedToken *entity.RefreshToken,
	oldAccessTokenJTI string,
	oldAccessTokenExp time.Time,
) (*svc.RefreshResponse, error) {
	logger := s.logger

	// Get user
	user, err := s.userRepo.GetByID(ctx, storedToken.UserID)
	if err != nil {
		logger.Error("Failed to get user", zap.Error(err))
		return nil, fmt.Errorf("failed to get user: %w", err)
	}

	// Verify user can login
	if user.IsLocked() || user.IsBanned() || user.IsPendingVerification() {
		logger.Warn("User cannot login", zap.String("user_id", user.ID))
		return nil, apperrors.ErrAccountLocked()
	}

	// Generate new access token
	accessToken, err := s.jwtService.GenerateAccessToken(ctx, user)
	if err != nil {
		logger.Error("Failed to generate access token", zap.Error(err))
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	// Generate new refresh token
	newRefreshToken := generateSecureToken(32)
	newRefreshTokenHash := HashToken(newRefreshToken)

	// Add old access token to denylist
	if s.tokenCache != nil && oldAccessTokenJTI != "" {
		remainingTTL := time.Until(oldAccessTokenExp)
		if remainingTTL > 0 {
			if err := s.tokenCache.AddToDenylist(ctx, oldAccessTokenJTI, remainingTTL); err != nil {
				logger.Error("Failed to add to denylist", zap.Error(err))
			}
		}
	}

	// Store old token hash before revocation
	oldTokenHash := storedToken.TokenHash

	// Mark old refresh token as revoked
	if err := s.refreshTokenRepo.RevokeByHash(ctx, oldTokenHash); err != nil {
		logger.Error("Failed to revoke old token", zap.Error(err))
		return nil, fmt.Errorf("failed to revoke old token: %w", err)
	}

	// Create new refresh token record
	newTokenID := uuid.New().String()
	newTokenEntity := &entity.RefreshToken{
		ID:         newTokenID,
		UserID:     storedToken.UserID,
		TokenHash:  newRefreshTokenHash,
		FamilyID:   storedToken.FamilyID,
		ReplacedBy: nil,
		DeviceID:   storedToken.DeviceID,
		DeviceInfo: storedToken.DeviceInfo,
		IPAddress:  storedToken.IPAddress,
		ExpiresAt:  time.Now().Add(constants.RefreshTokenTTLDefault),
		CreatedAt:  time.Now().UTC(),
	}

	if err := s.refreshTokenRepo.Create(ctx, newTokenEntity); err != nil {
		logger.Error("Failed to create refresh token", zap.Error(err))
		return nil, fmt.Errorf("failed to create refresh token: %w", err)
	}

	// Update replaced_by for audit trail
	if err := s.refreshTokenRepo.UpdateReplacedBy(ctx, storedToken.ID, newTokenID); err != nil {
		logger.Warn("Failed to update replaced_by", zap.Error(err))
	}

	// Store in grace period cache (10 second TTL)
	if s.gracePeriodCache != nil {
		if err := s.gracePeriodCache.Set(ctx, oldTokenHash, accessToken, newRefreshToken); err != nil {
			logger.Warn("Failed to set grace period cache", zap.Error(err))
		}
	}

	// Log the refresh
	if s.auditService != nil {
		_ = s.auditService.LogTokenRefresh(ctx, user.ID, storedToken.DeviceID)
	}

	logger.Info("Tokens rotated successfully",
		zap.String("user_id", user.ID),
		zap.String("old_token_id", storedToken.ID),
		zap.String("new_token_id", newTokenID),
	)

	return &svc.RefreshResponse{
		AccessToken:     accessToken,
		NewRefreshToken: newRefreshToken,
		ExpiresIn:       int64(constants.AccessTokenTTLDefault.Seconds()),
		TokenType:       "Bearer",
	}, nil
}

// handleTokenTheft handles the case when a refresh token is used after rotation
// This is called when the grace period has expired
func (s *RefreshService) handleTokenTheft(ctx context.Context, stolenToken *entity.RefreshToken) error {
	logger := s.logger.With(zap.String("user_id", stolenToken.UserID))

	// Revoke all refresh tokens for this user
	if err := s.refreshTokenRepo.RevokeAllForUser(ctx, stolenToken.UserID); err != nil {
		logger.Error("Failed to revoke all tokens", zap.Error(err))
		return fmt.Errorf("failed to revoke all tokens: %w", err)
	}

	// Set user revocation timestamp (force logout)
	if s.tokenCache != nil {
		now := time.Now()
		if err := s.tokenCache.SetRevokedBefore(ctx, stolenToken.UserID, now); err != nil {
			logger.Error("Failed to set revoked_before", zap.Error(err))
		}
	}

	// Log the token theft
	if s.auditService != nil {
		_ = s.auditService.LogTokenTheft(ctx, stolenToken.UserID, stolenToken.ID)
	}

	// Publish TOKEN_THEFT_DETECTED event for WebSocket cleanup
	if s.eventPublisher != nil {
		theftEvent := event.NewTokenTheftDetected(
			stolenToken.UserID,
			stolenToken.ID,
		)
		if err := s.eventPublisher.Publish(ctx, theftEvent); err != nil {
			logger.Warn("Failed to publish theft event", zap.Error(err))
		}
	}

	logger.Warn("Token theft handled - all tokens revoked")
	return nil
}

// RevokeToken revokes a specific refresh token
func (s *RefreshService) RevokeToken(ctx context.Context, userID, refreshToken string) error {
	logger := s.logger.With(zap.String("user_id", userID))

	tokenHash := HashToken(refreshToken)

	// Get the token to verify ownership
	token, err := s.refreshTokenRepo.GetByTokenHash(ctx, tokenHash)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return nil // Token doesn't exist, nothing to revoke
		}
		return fmt.Errorf("failed to get refresh token: %w", err)
	}

	// Verify token belongs to the user
	if token.UserID != userID {
		return apperrors.ErrUnauthorized
	}

	// Revoke the token
	if err := s.refreshTokenRepo.RevokeByHash(ctx, tokenHash); err != nil {
		logger.Error("Failed to revoke token", zap.Error(err))
		return fmt.Errorf("failed to revoke token: %w", err)
	}

	logger.Info("Token revoked successfully", zap.String("token_id", token.ID))
	return nil
}

// RevokeAllUserTokens revokes all tokens for a user (force logout)
func (s *RefreshService) RevokeAllUserTokens(ctx context.Context, userID string, reason string) error {
	logger := s.logger.With(zap.String("user_id", userID))

	// Revoke all refresh tokens
	if err := s.refreshTokenRepo.RevokeAllForUser(ctx, userID); err != nil {
		logger.Error("Failed to revoke all tokens", zap.Error(err))
		return fmt.Errorf("failed to revoke all tokens: %w", err)
	}

	// Set revocation timestamp
	if s.tokenCache != nil {
		now := time.Now()
		if err := s.tokenCache.SetRevokedBefore(ctx, userID, now); err != nil {
			logger.Error("Failed to set revoked_before", zap.Error(err))
		}
	}

	// Log the force logout
	if s.auditService != nil {
		_ = s.auditService.LogForceLogout(ctx, userID, reason, "")
	}

	// Publish force logout event
	if s.eventPublisher != nil {
		forceLogoutEvent := event.NewForceLogoutEvent(userID, reason)
		_ = s.eventPublisher.Publish(ctx, forceLogoutEvent)
	}

	logger.Info("All sessions revoked for user", zap.String("reason", reason))
	return nil
}

package authentication

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

// RefreshTokenUsecase handles token refresh
type RefreshTokenUsecase struct {
	sessionRepo      repository.SessionRepository
	refreshTokenRepo repository.RefreshTokenRepository
	userService      common.UserService
	eventPublisher   common.EventPublisher
	tokenGenerator   common.TokenGenerator
	logger           *zap.Logger
}

// NewRefreshTokenUsecase creates new refresh token usecase
func NewRefreshTokenUsecase(
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	userService common.UserService,
	eventPublisher common.EventPublisher,
	tokenGenerator common.TokenGenerator,
	logger *zap.Logger,
) *RefreshTokenUsecase {
	return &RefreshTokenUsecase{
		sessionRepo:      sessionRepo,
		refreshTokenRepo: refreshTokenRepo,
		userService:      userService,
		eventPublisher:   eventPublisher,
		tokenGenerator:   tokenGenerator,
		logger:           logger,
	}
}

// Execute refreshes access token using refresh token
func (uc *RefreshTokenUsecase) Execute(ctx context.Context, req *dto.RefreshTokenRequest, reqCtx *common.RequestContext) (*dto.RefreshTokenResponse, error) {
	// Step 1: Parse refresh token
	// Format: base64(user_id:device_id:random_string)
	tokenParts := strings.Split(req.RefreshToken, ":")
	if len(tokenParts) != 3 {
		return nil, serr.ErrRefreshTokenInvalid
	}

	userID, err := uuid.Parse(tokenParts[0])
	if err != nil {
		return nil, serr.ErrRefreshTokenInvalid
	}

	deviceID := tokenParts[1]
	tokenHash := utils.SHA256Hash(req.RefreshToken)

	// Step 2: Get refresh token from Redis
	tokenInfo, err := uc.refreshTokenRepo.Get(ctx, userID, deviceID)
	if err != nil {
		return nil, serr.ErrRefreshTokenInvalid
	}

	storedHash := tokenInfo.TokenHash
	rotatedFrom := tokenInfo.RotatedFrom

	// Step 3: Compare token hash
	if tokenHash != storedHash {
		// Check rotated_from (grace period for recently rotated token)
		if rotatedFrom != nil && tokenHash != *rotatedFrom {
			// Token theft detected!
			uc.logger.Warn("Possible token theft detected",
				zap.String("user_id", userID.String()),
				zap.String("device_id", deviceID))

			// Revoke ALL sessions for user
			if err := uc.sessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
				uc.logger.Error("Failed to revoke all sessions", zap.Error(err))
			}

			if err := uc.refreshTokenRepo.DeleteAllByUserID(ctx, userID); err != nil {
				uc.logger.Error("Failed to delete all refresh tokens", zap.Error(err))
			}

			// Publish security alert
			uc.publishSuspiciousActivity(ctx, userID, "TOKEN_THEFT", deviceID, reqCtx.DeviceInfo.IPAddress)

			return nil, serr.ErrRefreshTokenTheft
		}
		// Grace period - allow old token
	}

	// Step 4: Check user.active
	user, err := uc.userService.GetUserByID(ctx, userID)
	if err != nil {
		return nil, serr.ErrUserNotFound
	}

	if !user.Active {
		return nil, serr.ErrAccountDeactivated
	}

	// Step 5: Generate new token pair
	newAccessToken, newRefreshToken, err := uc.generateTokenPair(user, reqCtx)
	if err != nil {
		return nil, fmt.Errorf("failed to generate tokens: %w", err)
	}

	// Step 6: Update Redis with rotation
	newTokenHash := utils.SHA256Hash(newRefreshToken)
	remainTTL, err := uc.refreshTokenRepo.UpdateRotation(ctx, userID, deviceID, newTokenHash, tokenHash)
	if err != nil {
		uc.logger.Error("Failed to update token rotation", zap.Error(err))
		return nil, fmt.Errorf("failed to update token: %w", err)
	}

	uc.logger.Info("Token refreshed successfully",
		zap.String("user_id", userID.String()),
		zap.String("device_id", deviceID))

	return &dto.RefreshTokenResponse{
		AccessToken:  newAccessToken,
		RefreshToken: newRefreshToken,
		ExpiresIn:    remainTTL,
		TokenType:    "Bearer",
	}, nil
}

// ==================== HELPER METHODS ====================

func (uc *RefreshTokenUsecase) generateTokenPair(
	user *common.UserDTO,
	reqCtx *common.RequestContext,
) (string, string, error) {
	// Generate access token (JWT)
	claims := &entity.TokenClaims{
		JTI:               uuid.New().String(),
		Type:              entity.TokenTypeAccess,
		Subject:           user.UserID,
		Role:              user.Role,
		AccountType:       user.AccountType,
		RestrictionStatus: user.RestrictionStatus,
		ParentID:          user.ParentID,
		ExpiresAt:         time.Now().UTC().Add(accessTokenTTL).Unix(),
	}

	accessToken, err := uc.tokenGenerator.GenerateAccessToken(claims)
	if err != nil {
		return "", "", fmt.Errorf("failed to generate access token: %w", err)
	}

	// Generate refresh token (opaque)
	refreshToken, err := uc.tokenGenerator.GenerateRefreshToken()
	if err != nil {
		return "", "", fmt.Errorf("failed to generate refresh token: %w", err)
	}

	// Format: user_id:device_id:random
	deviceID := "unknown"
	if reqCtx.DeviceInfo.DeviceID != nil {
		deviceID = *reqCtx.DeviceInfo.DeviceID
	}
	fullRefreshToken := fmt.Sprintf("%s:%s:%s", user.UserID.String(), deviceID, refreshToken)

	return accessToken, fullRefreshToken, nil
}

func (uc *RefreshTokenUsecase) publishSuspiciousActivity(ctx context.Context, userID uuid.UUID, activityType string, deviceID, ipAddress string) error {
	event := &common.SuspiciousActivityEvent{
		UserID:       &userID,
		ActivityType: activityType,
		DeviceID:     deviceID,
		IPAddress:    ipAddress,
		Timestamp:    time.Now().UTC(),
	}
	return uc.eventPublisher.PublishSuspiciousActivity(ctx, event)
}

package service

import (
	"context"
	"crypto/rand"
	"encoding/hex"
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

type AuthService struct {
	userRepo         port.UserRepository
	refreshTokenRepo port.RefreshTokenRepository
	tokenCache       port.TokenCache
	passwordHasher   port.PasswordHasher
	failedLogin      port.FailedLoginCache
	eventPublisher   port.EventPublisher
	auditService     *AuditService
	jwtService       *JWTService
	logger           *zap.Logger
}

func NewAuthService(
	userRepo port.UserRepository,
	refreshTokenRepo port.RefreshTokenRepository,
	tokenCache port.TokenCache,
	passwordHasher port.PasswordHasher,
	failedLogin port.FailedLoginCache,
	eventPublisher port.EventPublisher,
	auditService *AuditService,
	jwtService *JWTService,
	logger *zap.Logger,
) *AuthService {
	return &AuthService{
		userRepo:         userRepo,
		refreshTokenRepo: refreshTokenRepo,
		tokenCache:       tokenCache,
		passwordHasher:   passwordHasher,
		failedLogin:      failedLogin,
		eventPublisher:   eventPublisher,
		auditService:     auditService,
		jwtService:       jwtService,
		logger:           logger.With(zap.String("service", "AuthService")),
	}
}

// Login authenticates a user and returns tokens
func (s *AuthService) Login(ctx context.Context, req *svc.LoginRequest) (*svc.LoginResponse, error) {
	logger := s.logger.With(
		zap.String("email", maskEmail(req.Email)),
		zap.String("ip", req.IPAddress),
		zap.String("device_id", req.DeviceID),
	)

	logger.Info("Login attempt started")

	user, err := s.userRepo.GetByEmail(ctx, req.Email)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			s.failedLogin.Increment(ctx, req.Email)
			s.failedLogin.Increment(ctx, req.IPAddress)
			logger.Warn("Login failed - user not found")
			return nil, apperrors.ErrInvalidCredentials()
		}
		logger.Error("Login failed - database error", zap.Error(err))
		return nil, fmt.Errorf("failed to get user: %w", err)
	}

	if user.IsBanned() {
		logger.Warn("Login failed - account banned", zap.String("user_id", user.ID))
		return nil, apperrors.ErrAccountBanned()
	}

	if user.IsLocked() {
		logger.Warn("Login failed - account locked", zap.String("user_id", user.ID))
		return nil, apperrors.ErrAccountLocked()
	}

	if user.IsPendingVerification() {
		logger.Warn("Login failed - email not verified", zap.String("user_id", user.ID))
		return nil, apperrors.ErrEmailNotVerified()
	}

	isLocked, err := s.failedLogin.IsLocked(ctx, req.Email)
	if err != nil {
		logger.Error("Failed to check lock status", zap.Error(err))
		return nil, fmt.Errorf("failed to check lock status: %w", err)
	}
	if isLocked {
		logger.Warn("Login failed - account locked by brute-force protection", zap.String("user_id", user.ID))
		return nil, apperrors.ErrAccountLocked()
	}

	valid := s.passwordHasher.Verify(req.Password, user.PasswordHash)
	if !valid {
		_, shouldLock, err := s.failedLogin.IncrementAndCheck(ctx, req.Email)
		if err != nil {
			logger.Error("Failed to increment failed login counter", zap.Error(err))
			return nil, fmt.Errorf("failed to increment failed login: %w", err)
		}

		s.failedLogin.Increment(ctx, req.IPAddress)

		if s.eventPublisher != nil {
			failedLoginEvent := event.NewLoginFailed(user.ID, user.Email.String(), req.IPAddress, req.DeviceID, req.DeviceInfo)
			_ = s.eventPublisher.Publish(ctx, failedLoginEvent)
		}

		if shouldLock {
			if err := s.userRepo.LockAccount(ctx, user.ID); err != nil {
				logger.Error("Failed to lock account", zap.Error(err))
				return nil, fmt.Errorf("failed to lock account: %w", err)
			}

			if s.eventPublisher != nil {
				lockEvent := event.NewUserAccountLocked(user.ID, user.Email.String(), "FAILED_LOGIN_ATTEMPTS")
				_ = s.eventPublisher.Publish(ctx, lockEvent)
			}

			logger.Warn("Account locked due to failed login attempts", zap.String("user_id", user.ID))
			return nil, apperrors.ErrAccountLocked()
		}

		logger.Warn("Login failed - invalid credentials", zap.String("user_id", user.ID))
		return nil, apperrors.ErrInvalidCredentials()
	}

	s.failedLogin.Reset(ctx, req.Email)
	s.failedLogin.Reset(ctx, req.IPAddress)

	if user.FailedLoginAttempts > 0 {
		user.ResetFailedAttempts()
	}
	now := time.Now().UTC()
	user.LastLoginAt = &now
	user.LastLoginIP = req.IPAddress
	if err := s.userRepo.Update(ctx, user); err != nil {
		logger.Error("Failed to update user last login", zap.Error(err))
	}

	accessToken, err := s.jwtService.GenerateAccessToken(ctx, user)
	if err != nil {
		logger.Error("Failed to generate access token", zap.Error(err))
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	if s.auditService != nil {
		_ = s.auditService.LogLoginSuccess(ctx, user.ID, user.Email.String(), req.IPAddress, req.DeviceID, req.DeviceInfo)
	}

	if s.eventPublisher != nil {
		loginSuccessEvent := event.NewLoginSuccess(user.ID, user.Email.String(), req.IPAddress, req.DeviceID, req.DeviceInfo)
		_ = s.eventPublisher.Publish(ctx, loginSuccessEvent)
	}

	logger.Info("Login successful", zap.String("user_id", user.ID), zap.Strings("roles", user.Roles))

	return &svc.LoginResponse{
		AccessToken: accessToken,
		ExpiresIn:  int64(constants.AccessTokenTTLDefault.Seconds()),
		TokenType:  "Bearer",
		User: &svc.UserInfo{
			ID:     user.ID,
			Email:  user.Email.String(),
			Roles:  user.Roles,
			Status: string(user.Status),
		},
	}, nil
}

// GetRefreshTokenForCookie returns a new refresh token for HTTP-only cookie
func (s *AuthService) GetRefreshTokenForCookie(ctx context.Context, userID string) (string, time.Time, error) {
	refreshToken := generateSecureToken(32)
	refreshTokenHash := HashToken(refreshToken)

	expiresAt := time.Now().Add(constants.RefreshTokenTTLDefault)

	tokenEntity := &entity.RefreshToken{
		ID:         uuid.New().String(),
		UserID:     userID,
		TokenHash:  refreshTokenHash,
		FamilyID:   generateFamilyID(),
		DeviceID:   "",
		DeviceInfo: "",
		ExpiresAt:  expiresAt,
		CreatedAt:  time.Now().UTC(),
	}

	if err := s.refreshTokenRepo.Create(ctx, tokenEntity); err != nil {
		return "", time.Time{}, fmt.Errorf("failed to save refresh token: %w", err)
	}

	return refreshToken, expiresAt, nil
}

// Logout handles user logout
func (s *AuthService) Logout(ctx context.Context, req *svc.LogoutRequest) error {
	logger := s.logger.With(zap.String("user_id", req.UserID))

	if s.tokenCache != nil && req.AccessTokenJTI != "" {
		remainingTTL := time.Until(req.AccessTokenExp)
		if remainingTTL > 0 {
			if err := s.tokenCache.AddToDenylist(ctx, req.AccessTokenJTI, remainingTTL); err != nil {
				logger.Error("Failed to add token to denylist", zap.Error(err))
				return fmt.Errorf("failed to add token to denylist: %w", err)
			}
		}
	}

	if req.RefreshToken != "" {
		refreshTokenHash := HashToken(req.RefreshToken)
		if err := s.refreshTokenRepo.RevokeByHash(ctx, refreshTokenHash); err != nil {
			logger.Error("Failed to revoke refresh token", zap.Error(err))
		}
	}

	if s.auditService != nil {
		_ = s.auditService.LogLogout(ctx, req.UserID, "")
	}

	if s.eventPublisher != nil {
		logoutEvent := event.NewLogoutEvent(req.UserID)
		_ = s.eventPublisher.Publish(ctx, logoutEvent)
	}

	logger.Info("User logged out successfully")
	return nil
}

// ForceLogoutAll revokes all tokens for a user
func (s *AuthService) ForceLogoutAll(ctx context.Context, userID, reason string) error {
	logger := s.logger.With(
		zap.String("user_id", userID),
		zap.String("reason", reason),
	)

	if err := s.refreshTokenRepo.RevokeAllForUser(ctx, userID); err != nil {
		logger.Error("Failed to revoke all refresh tokens", zap.Error(err))
		return fmt.Errorf("failed to revoke all tokens: %w", err)
	}

	if s.tokenCache != nil {
		now := time.Now()
		if err := s.tokenCache.SetRevokedBefore(ctx, userID, now); err != nil {
			logger.Error("Failed to set revoked_before timestamp", zap.Error(err))
		}
	}

	if s.auditService != nil {
		_ = s.auditService.LogForceLogout(ctx, userID, reason, "")
	}

	if s.eventPublisher != nil {
		forceLogoutEvent := event.NewForceLogoutEvent(userID, reason)
		_ = s.eventPublisher.Publish(ctx, forceLogoutEvent)
	}

	logger.Info("All sessions revoked for user")
	return nil
}

// RefreshTokens refreshes access and refresh tokens
func (s *AuthService) RefreshTokens(ctx context.Context, refreshToken string, oldAccessTokenJTI string, oldAccessTokenExp time.Time) (*svc.RefreshResponse, error) {
	logger := s.logger

	refreshTokenHash := HashToken(refreshToken)

	token, err := s.refreshTokenRepo.GetByTokenHash(ctx, refreshTokenHash)
	if err != nil {
		logger.Warn("Refresh token not found")
		return nil, apperrors.ErrInvalidToken()
	}

	refreshTokenEntity := token
	if refreshTokenEntity == nil {
		logger.Error("Refresh token is nil")
		return nil, apperrors.ErrInvalidToken()
	}

	if !refreshTokenEntity.IsActive() {
		logger.Warn("Refresh token is not active")
		return nil, apperrors.ErrInvalidToken()
	}

	user, err := s.userRepo.GetByID(ctx, refreshTokenEntity.UserID)
	if err != nil {
		logger.Error("Failed to get user", zap.Error(err))
		return nil, fmt.Errorf("failed to get user: %w", err)
	}

	refreshTokenEntity.Revoke()
	refreshTokenEntity.MarkReplaced(uuid.New().String())
	_ = s.refreshTokenRepo.UpdateReplacedBy(ctx, refreshTokenEntity.ID, *refreshTokenEntity.ReplacedBy)

	if s.tokenCache != nil && oldAccessTokenJTI != "" {
		remainingTTL := time.Until(oldAccessTokenExp)
		if remainingTTL > 0 {
			_ = s.tokenCache.AddToDenylist(ctx, oldAccessTokenJTI, remainingTTL)
		}
	}

	newAccessToken, err := s.jwtService.GenerateAccessToken(ctx, user)
	if err != nil {
		logger.Error("Failed to generate access token", zap.Error(err))
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	newRefreshToken := generateSecureToken(32)
	newRefreshTokenHash := HashToken(newRefreshToken)
	newExpiresAt := time.Now().Add(constants.RefreshTokenTTLDefault)

	newTokenEntity := &entity.RefreshToken{
		ID:         uuid.New().String(),
		UserID:     user.ID,
		TokenHash:  newRefreshTokenHash,
		FamilyID:   refreshTokenEntity.FamilyID,
		ReplacedBy: nil,
		DeviceID:   refreshTokenEntity.DeviceID,
		DeviceInfo: refreshTokenEntity.DeviceInfo,
		IPAddress:  refreshTokenEntity.IPAddress,
		ExpiresAt:  newExpiresAt,
		RevokedAt:  nil,
		CreatedAt:  time.Now().UTC(),
	}

	if err := s.refreshTokenRepo.Create(ctx, newTokenEntity); err != nil {
		logger.Error("Failed to create new refresh token", zap.Error(err))
		return nil, fmt.Errorf("failed to create refresh token: %w", err)
	}

	if s.auditService != nil {
		_ = s.auditService.LogTokenRefresh(ctx, user.ID, "")
	}

	return &svc.RefreshResponse{
		AccessToken:     newAccessToken,
		NewRefreshToken: newRefreshToken,
		ExpiresAt:       newExpiresAt,
		TokenType:       "Bearer",
	}, nil
}

func maskEmail(email string) string {
	if len(email) < 4 {
		return "***"
	}
	atIndex := -1
	for i, c := range email {
		if c == '@' {
			atIndex = i
			break
		}
	}
	if atIndex <= 1 {
		return email[:1] + "***"
	}
	return email[:2] + "***" + email[atIndex:]
}

func generateSecureToken(length int) string {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		panic(err)
	}
	return hex.EncodeToString(bytes)
}

func generateFamilyID() string {
	return time.Now().Format("20060102150405") + "-" + randomString(8)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}

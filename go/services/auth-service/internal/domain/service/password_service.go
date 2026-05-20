package service

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/common/validator"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// PasswordChangeRequest represents password change request
type PasswordChangeRequest struct {
	UserID          string
	CurrentPassword string
	NewPassword     string
	IPAddress       string
	DeviceInfo      string
}

// PasswordResetRequest represents password reset request
type PasswordResetRequest struct {
	ResetToken  string
	NewPassword string
	IPAddress   string
}

// ForgotPasswordRequest represents forgot password request
type ForgotPasswordRequest struct {
	Email     string
	IPAddress string
}

// PasswordService handles password management operations
type PasswordService struct {
	userRepo        port.UserRepository
	refreshTokenRepo port.RefreshTokenRepository
	tokenCache     port.TokenCache
	passwordHasher port.PasswordHasher
	resetTokenCache port.ResetTokenCache
	eventPublisher port.EventPublisher
	auditService   *AuditService
	logger         *zap.Logger
}

func NewPasswordService(
	userRepo port.UserRepository,
	refreshTokenRepo port.RefreshTokenRepository,
	tokenCache port.TokenCache,
	passwordHasher port.PasswordHasher,
	resetTokenCache port.ResetTokenCache,
	eventPublisher port.EventPublisher,
	auditService *AuditService,
	logger *zap.Logger,
) *PasswordService {
	return &PasswordService{
		userRepo:        userRepo,
		refreshTokenRepo: refreshTokenRepo,
		tokenCache:      tokenCache,
		passwordHasher:  passwordHasher,
		resetTokenCache: resetTokenCache,
		eventPublisher:  eventPublisher,
		auditService:    auditService,
		logger:          logger.With(zap.String("service", "PasswordService")),
	}
}

// ChangePassword changes user password
func (s *PasswordService) ChangePassword(ctx context.Context, req *PasswordChangeRequest) error {
	logger := s.logger.With(
		zap.String("user_id", req.UserID),
		zap.String("ip", req.IPAddress),
	)

	// Get user
	user, err := s.userRepo.GetByID(ctx, req.UserID)
	if err != nil {
		logger.Error("User not found", zap.Error(err))
		return fmt.Errorf("user not found")
	}

	// Verify current password
	if !s.passwordHasher.Verify(req.CurrentPassword, user.PasswordHash) {
		logger.Warn("Invalid current password")
		if s.auditService != nil {
			_ = s.auditService.LogPasswordChangeFailed(ctx, req.UserID, req.IPAddress)
		}
		return fmt.Errorf("invalid current password")
	}

	// Validate new password
	if valid, msg := validator.ValidatePassword(req.NewPassword); !valid {
		logger.Warn("Weak password", zap.String("reason", msg))
		return fmt.Errorf("weak password: %s", msg)
	}

	// Check password history (simplified - in production, check against password history)
	if err := s.checkPasswordHistory(ctx, req.UserID, req.NewPassword); err != nil {
		return err
	}

	// Hash new password
	newHash, err := s.passwordHasher.Hash(req.NewPassword)
	if err != nil {
		logger.Error("Failed to hash password", zap.Error(err))
		return fmt.Errorf("failed to hash password")
	}

	// Update password
	user.PasswordHash = newHash
	if err := s.userRepo.Update(ctx, user); err != nil {
		logger.Error("Failed to update password", zap.Error(err))
		return fmt.Errorf("failed to update password")
	}

	// Force logout all other sessions
	if err := s.forceLogoutAll(ctx, req.UserID); err != nil {
		logger.Error("Failed to force logout", zap.Error(err))
	}

	// Log password change
	if s.auditService != nil {
		_ = s.auditService.LogPasswordChanged(ctx, req.UserID, req.IPAddress, req.DeviceInfo)
	}

	// Publish password changed event
	if s.eventPublisher != nil {
		passwordChanged := event.NewUserPasswordChanged(user.ID, user.Email.String())
		_ = s.eventPublisher.Publish(ctx, passwordChanged)
	}

	logger.Info("Password changed successfully")
	return nil
}

// RequestPasswordReset initiates password reset flow
func (s *PasswordService) RequestPasswordReset(ctx context.Context, req *ForgotPasswordRequest) error {
	logger := s.logger.With(
		zap.String("email", maskEmailForLog(req.Email)),
		zap.String("ip", req.IPAddress),
	)

	// Get user by email (don't reveal if email exists or not)
	user, err := s.userRepo.GetByEmail(ctx, req.Email)
	if err != nil {
		// Don't reveal if email exists
		logger.Info("Password reset requested for non-existent email")
		return nil
	}

	// Generate reset token
	resetToken := generateResetToken()
	resetTokenHash := hashResetToken(resetToken)

	// Store in Redis
	if err := s.resetTokenCache.Set(ctx, resetTokenHash, user.ID); err != nil {
		logger.Error("Failed to store reset token", zap.Error(err))
		return fmt.Errorf("failed to store reset token")
	}

	// Publish password reset requested event (for email sending)
	if s.eventPublisher != nil {
		resetEvent := event.NewPasswordResetRequested(user.ID, user.Email.String(), resetToken)
		_ = s.eventPublisher.Publish(ctx, resetEvent)
	}

	// Log request
	if s.auditService != nil {
		_ = s.auditService.LogPasswordResetRequested(ctx, user.ID, req.IPAddress)
	}

	logger.Info("Password reset requested")
	return nil
}

// ResetPassword resets password using reset token
func (s *PasswordService) ResetPassword(ctx context.Context, req *PasswordResetRequest) error {
	logger := s.logger.With(zap.String("ip", req.IPAddress))

	// Hash the reset token
	resetTokenHash := hashResetToken(req.ResetToken)

	// Get user ID from cache (atomic verify and delete)
	userID, err := s.resetTokenCache.Verify(ctx, resetTokenHash)
	if err != nil {
		logger.Error("Failed to verify reset token", zap.Error(err))
		return fmt.Errorf("failed to verify reset token")
	}

	if userID == "" {
		logger.Warn("Invalid or expired reset token")
		if s.auditService != nil {
			_ = s.auditService.LogPasswordResetFailed(ctx, "", req.IPAddress, "INVALID_TOKEN")
		}
		return fmt.Errorf("invalid or expired reset token")
	}

	// Get user
	user, err := s.userRepo.GetByID(ctx, userID)
	if err != nil {
		logger.Error("User not found", zap.Error(err))
		return fmt.Errorf("user not found")
	}

	// Validate new password
	if valid, msg := validator.ValidatePassword(req.NewPassword); !valid {
		logger.Warn("Weak password", zap.String("reason", msg))
		return fmt.Errorf("weak password: %s", msg)
	}

	// Hash new password
	newHash, err := s.passwordHasher.Hash(req.NewPassword)
	if err != nil {
		logger.Error("Failed to hash password", zap.Error(err))
		return fmt.Errorf("failed to hash password")
	}

	// Update password
	user.PasswordHash = newHash
	if err := s.userRepo.Update(ctx, user); err != nil {
		logger.Error("Failed to update password", zap.Error(err))
		return fmt.Errorf("failed to update password")
	}

	// Force logout all sessions
	if err := s.forceLogoutAll(ctx, user.ID); err != nil {
		logger.Error("Failed to force logout", zap.Error(err))
	}

	// Log password reset
	if s.auditService != nil {
		_ = s.auditService.LogPasswordResetSuccess(ctx, user.ID, req.IPAddress)
	}

	// Publish password changed event
	if s.eventPublisher != nil {
		passwordChanged := event.NewUserPasswordChanged(user.ID, user.Email.String())
		_ = s.eventPublisher.Publish(ctx, passwordChanged)
	}

	logger.Info("Password reset successfully")
	return nil
}

// forceLogoutAll revokes all tokens for a user
func (s *PasswordService) forceLogoutAll(ctx context.Context, userID string) error {
	logger := s.logger.With(zap.String("user_id", userID))

	// Revoke all refresh tokens
	if err := s.refreshTokenRepo.RevokeAllForUser(ctx, userID); err != nil {
		logger.Error("Failed to revoke tokens", zap.Error(err))
		return fmt.Errorf("failed to revoke tokens: %w", err)
	}

	// Set revocation timestamp
	now := time.Now()
	if s.tokenCache != nil {
		if err := s.tokenCache.SetRevokedBefore(ctx, userID, now); err != nil {
			logger.Error("Failed to set revoked_before", zap.Error(err))
		}
	}

	return nil
}

// checkPasswordHistory checks if password was used recently
func (s *PasswordService) checkPasswordHistory(ctx context.Context, userID string, newPassword string) error {
	// Simplified implementation - in production, check against password history stored in database
	return nil
}

// GetResetTokenForEmail returns the reset token for sending via email
// This is a temporary method - in production, the token should be sent via email service
func (s *PasswordService) GetResetTokenForEmail(ctx context.Context, email string) (string, error) {
	user, err := s.userRepo.GetByEmail(ctx, email)
	if err != nil {
		return "", nil // Don't reveal if email exists
	}

	resetToken := generateResetToken()
	resetTokenHash := hashResetToken(resetToken)

	if err := s.resetTokenCache.Set(ctx, resetTokenHash, user.ID); err != nil {
		return "", err
	}

	return resetToken, nil
}

// generateResetToken generates a secure reset token
func generateResetToken() string {
	bytes := make([]byte, constants.ResetTokenLength)
	if _, err := rand.Read(bytes); err != nil {
		panic(err)
	}
	return hex.EncodeToString(bytes)
}

// hashResetToken creates SHA-256 hash of a reset token
func hashResetToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}

func maskEmailForLog(email string) string {
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

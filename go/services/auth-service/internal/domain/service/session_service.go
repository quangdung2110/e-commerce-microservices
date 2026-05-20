package service

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/port"
)

// SessionInfo represents a user session
type SessionInfo struct {
	ID         string
	DeviceID   string
	DeviceInfo string
	IPAddress  string
	CreatedAt  time.Time
	ExpiresAt  time.Time
	IsCurrent  bool
}

// SessionService handles session operations
type SessionService struct {
	refreshTokenRepo port.RefreshTokenRepository
	tokenCache      port.TokenCache
	auditService    *AuditService
	logger          *zap.Logger
}

func NewSessionService(
	refreshTokenRepo port.RefreshTokenRepository,
	tokenCache port.TokenCache,
	auditService *AuditService,
	logger *zap.Logger,
) *SessionService {
	return &SessionService{
		refreshTokenRepo: refreshTokenRepo,
		tokenCache:       tokenCache,
		auditService:     auditService,
		logger:           logger.With(zap.String("service", "SessionService")),
	}
}

// ListSessions returns all active sessions for a user
func (s *SessionService) ListSessions(ctx context.Context, userID, currentTokenHash string) ([]*SessionInfo, error) {
	tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		s.logger.Error("Failed to get active tokens", zap.String("user_id", userID), zap.Error(err))
		return nil, fmt.Errorf("failed to get active tokens: %w", err)
	}

	sessions := make([]*SessionInfo, 0, len(tokens))
	for _, token := range tokens {
		session := &SessionInfo{
			ID:         token.ID,
			DeviceID:   token.DeviceID,
			DeviceInfo: token.DeviceInfo,
			IPAddress:  token.IPAddress,
			CreatedAt:  token.CreatedAt,
			ExpiresAt:  token.ExpiresAt,
			IsCurrent:  token.TokenHash == currentTokenHash,
		}
		sessions = append(sessions, session)
	}

	return sessions, nil
}

// GetSessionByID retrieves a specific session
func (s *SessionService) GetSessionByID(ctx context.Context, userID, sessionID string) (*SessionInfo, error) {
	token, err := s.refreshTokenRepo.GetByID(ctx, sessionID)
	if err != nil {
		s.logger.Error("Failed to get session", zap.String("session_id", sessionID), zap.Error(err))
		return nil, fmt.Errorf("failed to get session: %w", err)
	}

	// Verify ownership
	if token.UserID != userID {
		return nil, fmt.Errorf("session does not belong to user")
	}

	return &SessionInfo{
		ID:         token.ID,
		DeviceID:   token.DeviceID,
		DeviceInfo: token.DeviceInfo,
		IPAddress:  token.IPAddress,
		CreatedAt:  token.CreatedAt,
		ExpiresAt:  token.ExpiresAt,
	}, nil
}

// RevokeSession revokes a specific session
func (s *SessionService) RevokeSession(ctx context.Context, userID, sessionID, currentTokenHash string) error {
	logger := s.logger.With(
		zap.String("user_id", userID),
		zap.String("session_id", sessionID),
	)

	// Get the session token
	token, err := s.refreshTokenRepo.GetByID(ctx, sessionID)
	if err != nil {
		logger.Error("Session not found", zap.Error(err))
		return fmt.Errorf("session not found")
	}

	// Verify the session belongs to the user
	if token.UserID != userID {
		logger.Warn("Attempted to revoke session belonging to another user")
		return fmt.Errorf("forbidden: session does not belong to user")
	}

	// Don't allow revoking current session
	if token.TokenHash == currentTokenHash {
		return fmt.Errorf("cannot revoke current session: use logout instead")
	}

	// Revoke the refresh token
	if err := s.refreshTokenRepo.Revoke(ctx, sessionID); err != nil {
		logger.Error("Failed to revoke session", zap.Error(err))
		return fmt.Errorf("failed to revoke session: %w", err)
	}

	// Log the revocation
	if s.auditService != nil {
		_ = s.auditService.LogSessionRevoked(ctx, userID, sessionID, token.DeviceInfo)
	}

	logger.Info("Session revoked successfully")
	return nil
}

// RevokeAllSessions revokes all sessions except the current one
func (s *SessionService) RevokeAllSessions(ctx context.Context, userID, currentTokenHash string) error {
	logger := s.logger.With(zap.String("user_id", userID))

	// Get all active tokens
	tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		logger.Error("Failed to get active tokens", zap.Error(err))
		return fmt.Errorf("failed to get active tokens: %w", err)
	}

	// Revoke all except current
	revokedCount := 0
	for _, token := range tokens {
		if token.TokenHash != currentTokenHash {
			if err := s.refreshTokenRepo.Revoke(ctx, token.ID); err != nil {
				logger.Error("Failed to revoke session", zap.String("session_id", token.ID), zap.Error(err))
				continue
			}
			revokedCount++
		}
	}

	// Log the mass revocation
	if s.auditService != nil {
		_ = s.auditService.LogAllSessionsRevoked(ctx, userID, revokedCount)
	}

	logger.Info("All other sessions revoked", zap.Int("revoked_count", revokedCount))
	return nil
}

// RevokeSessionByDeviceID revokes a specific device's session
func (s *SessionService) RevokeSessionByDeviceID(ctx context.Context, userID, deviceID, currentTokenHash string) error {
	logger := s.logger.With(
		zap.String("user_id", userID),
		zap.String("device_id", deviceID),
	)

	tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		logger.Error("Failed to get active tokens", zap.Error(err))
		return fmt.Errorf("failed to get active tokens: %w", err)
	}

	for _, token := range tokens {
		if token.DeviceID == deviceID {
			// Don't allow revoking current session
			if token.TokenHash == currentTokenHash {
				return fmt.Errorf("cannot revoke current session: use logout instead")
			}

			if err := s.refreshTokenRepo.Revoke(ctx, token.ID); err != nil {
				logger.Error("Failed to revoke session", zap.Error(err))
				return fmt.Errorf("failed to revoke session: %w", err)
			}

			if s.auditService != nil {
				_ = s.auditService.LogSessionRevoked(ctx, userID, token.ID, token.DeviceInfo)
			}

			logger.Info("Session revoked by device ID")
			return nil
		}
	}

	return fmt.Errorf("session for device not found")
}

package repository

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// SessionRepository defines the interface for session persistence operations
type SessionRepository interface {
	// Create creates a new session
	Create(ctx context.Context, session *entity.Session) error

	// GetByID retrieves a session by ID
	GetByID(ctx context.Context, sessionID uuid.UUID) (*entity.Session, error)

	// GetActiveByUserID retrieves all active sessions for a user
	GetActiveByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Session, error)

	// GetByUserIDAndDeviceID retrieves a session by user ID and device ID
	GetByUserIDAndDeviceID(ctx context.Context, userID uuid.UUID, deviceID string) (*entity.Session, error)

	// GetRecentByUser retrieves recent sessions for a user within time window (for impossible travel detection)
	GetRecentByUser(ctx context.Context, userID uuid.UUID, since time.Duration) ([]*entity.SessionWithLocation, error)

	// Update updates a session
	Update(ctx context.Context, session *entity.Session) error

	// UpdateLastActivity updates last_activity timestamp for a session
	UpdateLastActivity(ctx context.Context, sessionID uuid.UUID) error

	// Revoke marks a session as inactive
	RevokeByID(ctx context.Context, sessionID uuid.UUID) error

	// RevokeAllByUserID revokes all active sessions for a user
	RevokeAllByUserID(ctx context.Context, userID uuid.UUID) error

	// CountActiveByUserID counts active sessions for a user
	CountActiveByUserID(ctx context.Context, userID uuid.UUID) (int64, error)

	// DeleteExpired deletes expired sessions
	DeleteExpired(ctx context.Context) error

	// ========================= MODULE 3: TOKEN ROTATION =========================

	// FindByTokenHashForUpdate retrieves a session by token hash with row lock (FOR UPDATE)
	// Used during refresh token rotation to prevent race conditions
	FindByTokenHashForUpdate(ctx context.Context, tokenHash string) (*entity.Session, error)

	// CreateWithFamily creates a new session with family tracking
	CreateWithFamily(ctx context.Context, session *entity.Session, familyID uuid.UUID) error

	// RevokeWithGracePeriod revokes a session and sets grace period
	RevokeWithGracePeriod(ctx context.Context, tokenHash string, graceExpiresAt time.Time) error

	// MarkTokenAsUsed marks a token as used
	MarkTokenAsUsed(ctx context.Context, sessionID uuid.UUID) error

	// GetActiveSessionsWithGraceByUserID retrieves all active sessions and those in grace period
	GetActiveSessionsWithGraceByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Session, error)

	// DeleteExpiredGraceTokens deletes tokens past their grace period
	DeleteExpiredGraceTokens(ctx context.Context) error
}

// LoginAttemptRepository defines the interface for login attempt operations
type LoginAttemptRepository interface {
	// Create creates a new login attempt record
	Create(ctx context.Context, attempt *entity.LoginAttempt) error

	// GetByIP retrieves login attempts by IP address within a time window
	GetByIP(ctx context.Context, ipAddress string, since time.Time) ([]*entity.LoginAttempt, error)

	// GetByIdentifier retrieves login attempts by identifier (email/phone/username)
	GetByIdentifier(ctx context.Context, identifier string, since time.Time) ([]*entity.LoginAttempt, error)

	// GetByUserID retrieves login attempts by user ID
	GetByUserID(ctx context.Context, userID uuid.UUID, since time.Time, limit int) ([]*entity.LoginAttempt, error)

	// CountFailedByIP counts failed login attempts by IP
	CountFailedByIP(ctx context.Context, ipAddress string, since time.Time) (int64, error)

	// CountFailedByIdentifier counts failed login attempts by identifier
	CountFailedByIdentifier(ctx context.Context, identifier string, since time.Time) (int64, error)

	// GetSuspicious retrieves suspicious login attempts
	GetSuspicious(ctx context.Context, since time.Time, limit int) ([]*entity.LoginAttempt, error)
}

// PasswordResetTokenRepository defines the interface for password reset token operations
type PasswordResetTokenRepository interface {
	// Create creates a new password reset token (PostgreSQL for audit)
	Create(ctx context.Context, token *entity.PasswordResetToken) error

	// GetByTokenHash retrieves a token by its hash
	GetByTokenHash(ctx context.Context, tokenHash string) (*entity.PasswordResetToken, error)

	// GetByUserID retrieves active password reset token for a user
	GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.PasswordResetToken, error)

	// MarkAsUsed marks a token as used
	MarkAsUsed(ctx context.Context, tokenHash string) error

	//
	Update(ctx context.Context, token *entity.PasswordResetToken) error

	// Delete deletes a password reset token
	Delete(ctx context.Context, tokenHash string) error

	// DeleteByUserID deletes all tokens for a user
	DeleteByUserID(ctx context.Context, userID uuid.UUID) error

	// DeleteExpired deletes expired tokens
	DeleteExpired(ctx context.Context) error

	// DeleteUsed deletes used tokens older than a certain date
	DeleteUsed(ctx context.Context, olderThan time.Time) error
}

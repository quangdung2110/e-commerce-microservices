package repository

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// OTPRepository defines the interface for OTP operations (Redis)
type OTPRepository interface {
	// Store stores OTP data with TTL
	Store(ctx context.Context, otp *entity.OTP, ttl time.Duration) error

	// Get retrieves OTP data
	Get(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifier string) (*entity.OTP, error)

	// IncrementAttempts increments failed verification attempts
	IncrementAttempts(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifier string) error

	// Delete removes OTP data
	Delete(ctx context.Context, purpose entity.OTPPurpose, channel entity.OTPChannel, identifier string) error
}

// RateLimitRepository manages rate limiting in Redis (include login attemps, OTP email/sms requests)
type RateLimitRepository interface {
	// IncrementLoginAttempts increments login attempt counter for IP
	IncrementLoginAttempts(ctx context.Context, ip string) (int, error)

	// IncrementOTPRequests increments OTP request counter
	IncrementOTPRequests(ctx context.Context, key string) (int, error)

	Delete(ctx context.Context, key string) error

	// BlockIP blocks an IP address
	BlockIP(ctx context.Context, ip string, reason string, ttl time.Duration) error

	// IsIPBlocked checks if IP is blocked
	IsIPBlocked(ctx context.Context, ip string) (bool, error)

	// BlockUser blocks a user
	BlockUser(ctx context.Context, userID uuid.UUID, reason string, ttl time.Duration) error

	// IsUserBlocked checks if user is blocked
	IsUserBlocked(ctx context.Context, userID uuid.UUID) (bool, error)

	// CheckRateLimit checks if rate limit is exceeded for a key
	// Returns: allowed (bool), remaining tokens (int), lastSeen (int64 unix nano), suppressed (int64)
	CheckRateLimit(ctx context.Context, key string, limit int64) (bool, int64, int64, error)

	// IncrementRequestCount increments request count for rate limiting
	IncrementRequestCount(ctx context.Context, key string, window time.Duration) error

	// IncrementSuppressedCount increments suppressed log count
	IncrementSuppressedCount(ctx context.Context, key string) (int64, error)

	// GetAndResetSuppressedCount gets and resets suppressed count
	GetAndResetSuppressedCount(ctx context.Context, key string) (int64, error)

	// UpdateLastSeen updates last seen timestamp
	UpdateLastSeen(ctx context.Context, key string, timestamp int64) error

	// GetLastSeen gets last seen timestamp
	GetLastSeen(ctx context.Context, key string) (int64, error)

	// CheckPasswordResetRateLimit checks if password reset rate limit is exceeded (1/week)
	CheckPasswordResetRateLimit(ctx context.Context, identifier string) (bool, time.Duration, error)

	// SetPasswordResetRateLimit sets password reset rate limit
	SetPasswordResetRateLimit(ctx context.Context, identifier string, ttl time.Duration) error

	// SetActivityFlag sets activity flag for session (to reduce DB writes)
	SetActivityFlag(ctx context.Context, sessionID uuid.UUID, ttl time.Duration) error

	// CheckActivityFlag checks if activity flag exists
	CheckActivityFlag(ctx context.Context, sessionID uuid.UUID) (bool, error)
}

// TokenBlacklistRepository defines the interface for blacklist operations (Redis)
type BlacklistRepository interface {
	// Add adds a token to the blacklist
	Add(ctx context.Context, typ types.BlacklistType, parameter string, ttl time.Duration) error

	// IsBlacklisted checks if a token is blacklisted
	IsBlacklisted(ctx context.Context, typ types.BlacklistType, parameter string) (bool, error)

	Get(ctx context.Context, typ types.BlacklistType, parameter string) (string, error)

	// Remove removes a token from the blacklist
	Remove(ctx context.Context, typ types.BlacklistType, parameter string) error
}

// RefreshTokenRepository defines the interface for refresh token operations (Redis)
type RefreshTokenRepository interface {
	// Store stores refresh token with metadata
	Store(ctx context.Context, userID uuid.UUID, deviceID string, tokenHash string, sessionID uuid.UUID, ttl time.Duration) error

	// Get retrieves refresh token data
	Get(ctx context.Context, userID uuid.UUID, deviceID string) (*entity.RefreshTokenData, error)

	// Delete removes refresh token
	Delete(ctx context.Context, userID uuid.UUID, deviceID string) error

	// DeleteAllByUser removes all refresh tokens for a user
	DeleteAllByUserID(ctx context.Context, userID uuid.UUID) error

	// UpdateRotation updates token after rotation
	UpdateRotation(ctx context.Context, userID uuid.UUID, deviceID string, newTokenHash, oldTokenHash string) (int64, error)
}

// RegistrationSessionRepository defines the interface for registration session operations (Redis)
type RegistrationSessionRepository interface {
	// Create saves registration session data
	Create(ctx context.Context, email string, ttl time.Duration) (*entity.RegistrationSession, error)

	// Get retrieves registration session data
	Get(ctx context.Context, sessionID string) (*entity.RegistrationSession, error)

	// Update updates registration session data
	Update(ctx context.Context, session *entity.RegistrationSession) error

	// Delete removes registration session
	Delete(ctx context.Context, sessionID string) error
}

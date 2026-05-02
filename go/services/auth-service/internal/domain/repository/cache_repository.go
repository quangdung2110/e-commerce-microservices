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

// ========================= NEW INTERFACES FOR MODULE 1 & 2 =========================

// GraceCacheRepository defines the interface for grace period cache operations (Redis)
// Used during refresh token rotation to handle concurrent requests within 10-second window
type GraceCacheRepository interface {
	// Set stores grace period data with TTL (10 seconds)
	Set(ctx context.Context, tokenHash string, data *types.GracePeriodData) error

	// Get retrieves grace period data
	Get(ctx context.Context, tokenHash string) (*types.GracePeriodData, error)

	// Delete removes grace period data
	Delete(ctx context.Context, tokenHash string) error

	// Exists checks if grace period data exists
	Exists(ctx context.Context, tokenHash string) (bool, error)
}

// RevokeBeforeCacheRepository defines the interface for revoke-before cache operations (Redis)
// Used for force logout all sessions
type RevokeBeforeCacheRepository interface {
	// SetRevokedBefore sets the timestamp when all tokens should be considered revoked
	SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error

	// GetRevokedBefore gets the revoke-before timestamp
	GetRevokedBefore(ctx context.Context, userID string) (*time.Time, error)

	// IsRevokedBefore checks if a token issued at `iat` should be considered revoked
	IsRevokedBefore(ctx context.Context, userID string, iat time.Time) (bool, error)

	// Delete removes the revoke-before entry
	Delete(ctx context.Context, userID string) error
}

// AuditRepository defines the interface for audit log operations (PostgreSQL)
type AuditRepository interface {
	// Create creates a new audit log entry
	Create(ctx context.Context, log *entity.AuditLog) error

	// GetByID retrieves an audit log by ID
	GetByID(ctx context.Context, id uuid.UUID) (*entity.AuditLog, error)

	// GetByActorID retrieves audit logs by actor ID with pagination
	GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error)

	// GetByEventType retrieves audit logs by event type with pagination
	GetByEventType(ctx context.Context, eventType types.EventType, limit, offset int) ([]*entity.AuditLog, error)

	// GetByTimeRange retrieves audit logs within a time range
	GetByTimeRange(ctx context.Context, start, end time.Time, limit, offset int) ([]*entity.AuditLog, error)

	// GetByActorAndTimeRange retrieves audit logs by actor within time range
	GetByActorAndTimeRange(ctx context.Context, actorID string, start, end time.Time, limit, offset int) ([]*entity.AuditLog, error)

	// GetBySecurityLevel retrieves audit logs by security level
	GetBySecurityLevel(ctx context.Context, level types.SecurityLevel, limit, offset int) ([]*entity.AuditLog, error)

	// GetByCategory retrieves audit logs by category
	GetByCategory(ctx context.Context, category types.LogCategory, limit, offset int) ([]*entity.AuditLog, error)

	// Count returns total count of audit logs
	Count(ctx context.Context) (int64, error)

	// CountByActorID returns count of audit logs for an actor
	CountByActorID(ctx context.Context, actorID string) (int64, error)

	// DeleteOldLogs deletes audit logs older than the specified time
	DeleteOldLogs(ctx context.Context, olderThan time.Time) (int64, error)
}

// JwtKeyRepository defines the interface for JWT key operations (PostgreSQL)
type JwtKeyRepository interface {
	// Create creates a new JWT key
	Create(ctx context.Context, key *entity.JwtKey) error

	// GetByKid retrieves a key by its ID
	GetByKid(ctx context.Context, kid string) (*entity.JwtKey, error)

	// GetActive retrieves the active key for signing
	GetActive(ctx context.Context) (*entity.JwtKey, error)

	// GetAllActive retrieves all keys that can be used for verification (ACTIVE + PHASE_OUT)
	GetAllActive(ctx context.Context) ([]*entity.JwtKey, error)

	// UpdateStatus updates the status of a key
	UpdateStatus(ctx context.Context, kid string, status types.KeyStatus) error

	// Delete deletes a key
	Delete(ctx context.Context, kid string) error

	// DeleteExpired deletes expired keys
	DeleteExpired(ctx context.Context) (int64, error)

	// GetByStatus retrieves keys by status
	GetByStatus(ctx context.Context, status types.KeyStatus) ([]*entity.JwtKey, error)
}

// OutboxRepository defines the interface for outbox event operations (PostgreSQL)
type OutboxRepository interface {
	// Create creates a new outbox event
	Create(ctx context.Context, event *entity.OutboxEvent) error

	// GetByID retrieves an outbox event by ID
	GetByID(ctx context.Context, eventID uuid.UUID) (*entity.OutboxEvent, error)

	// GetPending retrieves pending events for processing
	GetPending(ctx context.Context, limit int) ([]*entity.OutboxEvent, error)

	// MarkAsPublished marks an event as published
	MarkAsPublished(ctx context.Context, eventID uuid.UUID) error

	// MarkAsFailed marks an event as failed
	MarkAsFailed(ctx context.Context, eventID uuid.UUID, errorMsg string) error

	// DeleteOldEvents deletes events older than the specified time
	DeleteOldEvents(ctx context.Context, olderThan time.Time) (int64, error)

	// Delete deletes an event
	Delete(ctx context.Context, eventID uuid.UUID) error
}

// DistributedLockRepository defines the interface for distributed locking (Redis)
type DistributedLockRepository interface {
	// Acquire attempts to acquire a lock with the given key and TTL
	// Returns true if lock was acquired, false otherwise
	Acquire(ctx context.Context, key string, ttl time.Duration) (bool, error)

	// Release releases a lock
	Release(ctx context.Context, key string) error

	// Extend extends the TTL of an existing lock
	Extend(ctx context.Context, key string, ttl time.Duration) error
}

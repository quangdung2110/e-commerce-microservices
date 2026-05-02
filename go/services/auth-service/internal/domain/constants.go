package domain

import "time"

// ========================= REDIS KEY PREFIXES =========================

const (
	// Token blacklist / denylist
	RedisKeyPrefixDenylist = "auth:denylist:"

	// Revoked-before timestamp for force logout all
	RedisKeyPrefixRevokedBefore = "auth:revoked_before:"

	// Grace period cache for refresh token rotation
	RedisKeyPrefixRefreshGrace = "auth:refresh_grace:"

	// Rate limiting keys
	RedisKeyPrefixRateLimitLogin  = "auth:rate_limit:login:"
	RedisKeyPrefixRateLimitReg    = "auth:rate_limit:register:"
	RedisKeyPrefixRateLimitForgot = "auth:rate_limit:forgot_pw:"
	RedisKeyPrefixRateLimitResend = "auth:rate_limit:resend_otp:"

	// OTP keys
	RedisKeyPrefixOTP      = "auth:otp:register:"
	RedisKeyPrefixOTPPhone = "auth:otp:phone:"

	// Password reset keys
	RedisKeyPrefixResetPW = "auth:reset_pw:"

	// Distributed lock prefix
	RedisKeyPrefixLock = "lock:"

	// JWKS cache key
	RedisKeyPrefixJwksCache = "auth:jwks:"

	// Refresh token by hash
	RedisKeyPrefixRefreshToken = "auth:refresh_token:"

	// Registration session
	RedisKeyPrefixRegistrationSession = "auth:registration:"
)

// Redis Pub/Sub channel
const (
	RedisChannelJwksUpdate = "auth:jwks:update"
)

// ========================= TOKEN TTLs =========================

const (
	AccessTokenTTL     = 15 * time.Minute
	RefreshTokenTTL    = 30 * 24 * time.Hour // 30 days
	GracePeriodTTL     = 10 * time.Second
	RateLimitWindow    = 5 * time.Minute
	OtpTTL             = 5 * time.Minute
	ResetPasswordTTL   = 15 * time.Minute
	RevokedBeforeTTL   = 16 * time.Minute // Access TTL + 1 min buffer
	LoginAttemptWindow = 15 * time.Minute
)

// ========================= KAFKA TOPICS =========================

const (
	KafkaTopicAuthEvents  = "t.auth.events"
	KafkaTopicAdminEvents = "t.admin.events"
)

// ========================= JWT CONFIGURATION =========================

const (
	JwtAlgorithm        = "RS256"
	JwtIssuer          = "auth-service"
	KeyRotationInterval = 90 * 24 * time.Hour // 3 months
	PhaseOutGraceDays   = 7                  // days to keep PHASE_OUT key
)

// ========================= DATABASE CLEANUP =========================

const (
	TokenCleanupRetention = 7 * 24 * time.Hour // 7 days after expiry/revoke
	AuditLogRetention     = 180 * 24 * time.Hour // 6 months
	OutboxRetentionDays   = 14
)

// ========================= RATE LIMITS =========================

const (
	MaxLoginAttempts      = 10
	MaxLoginBlockMinutes  = 60
	MaxRegisterPerHour    = 5
	MaxForgotPwPerHour    = 3
	MaxResendOtpPerMin   = 1
	MaxOtpAttempts        = 3
	MaxActiveSessions     = 5
)

// ========================= WORKER SCHEDULE =========================

const (
	TokenCleanupCron  = "0 2 * * *"   // 2 AM daily
	AuditCleanupCron  = "0 3 * * *"   // 3 AM daily
	KeyRotationCron   = "0 0 1 */3 *" // 00:00 on 1st of every 3rd month
)

// ========================= PAGINATION =========================

const (
	DefaultPageSize = 20
	MaxPageSize     = 100
)

// ========================= PASSWORD POLICY =========================

const (
	PasswordMinLength           = 8
	PasswordMaxLength           = 128
	BcryptCost                 = 12
	Argon2Memory               = 64 * 1024 // 64 MiB
	Argon2Iterations           = 3
	Argon2Parallelism          = 4
	Argon2KeyLength            = 32
)

// ========================= SESSION LIMITS =========================

const (
	MaxConcurrentSessions = 10
	SessionIdleTimeout   = 30 * time.Minute
)

// ========================= AUDIT LOG =========================

const (
	AuditLogMaxPayloadSize = 10000 // max characters in payload
)

// ========================= JWKS =========================

const (
	JwksCacheSize       = 100
	JwksCacheTTL        = 10 * time.Minute
	MinKeySizeBits      = 2048
	PreferredKeySizeBits = 4096
)

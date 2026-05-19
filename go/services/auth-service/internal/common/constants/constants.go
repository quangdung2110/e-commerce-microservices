package constants

import "time"

const (
	AccessTokenTTLDefault  = 15 * time.Minute
	RefreshTokenTTLDefault = 30 * 24 * time.Hour
	GracePeriodDefault     = 10 * time.Second
)

const (
	MaxFailedLoginAttempts = 5
	LockoutDuration        = 30 * time.Minute
	FailedLoginWindow      = 60 * time.Minute
	MaxConcurrentSessions  = 10
)

const (
	KeyRotationIntervalDefault = 30 * 24 * time.Hour
	KeyRetirementDelayDefault = 31 * 24 * time.Hour
	RSAKeySizeDefault         = 2048
)

const (
	RefreshTokenRetention = 7 * 24 * time.Hour
	AuditLogRetention     = 180 * 24 * time.Hour
	OutboxRetention       = 14 * 24 * time.Hour
	AutoUnlockInterval    = 5 * time.Minute
)

const (
	OTPLength       = 6
	OTPTTL          = 5 * time.Minute
	OTPResendMinGap = 60 * time.Second
)

const (
	ResetTokenLength = 32
	ResetTokenTTL    = 15 * time.Minute
)

const (
	StatusPendingVerification = "PENDING_VERIFICATION"
	StatusActive             = "ACTIVE"
	StatusLocked            = "LOCKED"
	StatusBanned            = "BANNED"
)

const (
	ActorTypeUser   = "USER"
	ActorTypeAdmin  = "ADMIN"
	ActorTypeSystem = "SYSTEM"
	ActorTypeGuest  = "GUEST"
)

const (
	SecurityLevelInfo      = "INFO"
	SecurityLevelWarning  = "WARNING"
	SecurityLevelCritical = "CRITICAL"
)

const (
	LogCategoryUser   = "USER"
	LogCategorySystem = "SYSTEM"
)

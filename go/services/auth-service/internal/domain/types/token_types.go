package types

import "time"

// ========================= ACTOR TYPES =========================

// ActorType represents the type of actor performing an action
type ActorType string

const (
	ActorTypeUser    ActorType = "USER"
	ActorTypeAdmin   ActorType = "ADMIN"
	ActorTypeSystem  ActorType = "SYSTEM"
	ActorTypeGuest   ActorType = "GUEST"
)

// String returns the string representation of ActorType
func (a ActorType) String() string {
	return string(a)
}

// IsValid checks if the actor type is valid
func (a ActorType) IsValid() bool {
	switch a {
	case ActorTypeUser, ActorTypeAdmin, ActorTypeSystem, ActorTypeGuest:
		return true
	default:
		return false
	}
}

// ========================= SECURITY LEVEL =========================

// SecurityLevel represents the severity level of an audit log entry
type SecurityLevel string

const (
	SecurityLevelInfo     SecurityLevel = "INFO"
	SecurityLevelWarning SecurityLevel = "WARNING"
	SecurityLevelCritical SecurityLevel = "CRITICAL"
)

// String returns the string representation of SecurityLevel
func (s SecurityLevel) String() string {
	return string(s)
}

// IsValid checks if the security level is valid
func (s SecurityLevel) IsValid() bool {
	switch s {
	case SecurityLevelInfo, SecurityLevelWarning, SecurityLevelCritical:
		return true
	default:
		return false
	}
}

// IsHighSeverity returns true if the security level is warning or critical
func (s SecurityLevel) IsHighSeverity() bool {
	return s == SecurityLevelWarning || s == SecurityLevelCritical
}

// ========================= LOG CATEGORY =========================

// LogCategory represents the category of an audit log entry
type LogCategory string

const (
	LogCategoryUser   LogCategory = "USER"
	LogCategorySystem LogCategory = "SYSTEM"
	LogCategoryAdmin  LogCategory = "ADMIN"
	LogCategoryAuth   LogCategory = "AUTH"
)

// String returns the string representation of LogCategory
func (c LogCategory) String() string {
	return string(c)
}

// IsValid checks if the log category is valid
func (c LogCategory) IsValid() bool {
	switch c {
	case LogCategoryUser, LogCategorySystem, LogCategoryAdmin, LogCategoryAuth:
		return true
	default:
		return false
	}
}

// ========================= JWT KEY STATUS =========================

// KeyStatus represents the status of a JWT signing key
type KeyStatus string

const (
	KeyStatusActive   KeyStatus = "ACTIVE"
	KeyStatusPhaseOut KeyStatus = "PHASE_OUT"
	KeyStatusRetired KeyStatus = "RETIRED"
)

// String returns the string representation of KeyStatus
func (k KeyStatus) String() string {
	return string(k)
}

// IsValid checks if the key status is valid
func (k KeyStatus) IsValid() bool {
	switch k {
	case KeyStatusActive, KeyStatusPhaseOut, KeyStatusRetired:
		return true
	default:
		return false
	}
}

// IsUsableForSigning returns true if the key can be used for signing tokens
func (k KeyStatus) IsUsableForSigning() bool {
	return k == KeyStatusActive
}

// IsUsableForVerification returns true if the key can be used for verifying tokens
func (k KeyStatus) IsUsableForVerification() bool {
	return k == KeyStatusActive || k == KeyStatusPhaseOut
}

// ========================= TOKEN STATUS =========================

// TokenStatus represents the status of a refresh token
type TokenStatus string

const (
	TokenStatusActive   TokenStatus = "ACTIVE"
	TokenStatusUsed     TokenStatus = "USED"
	TokenStatusRevoked  TokenStatus = "REVOKED"
	TokenStatusExpired  TokenStatus = "EXPIRED"
)

// String returns the string representation of TokenStatus
func (t TokenStatus) String() string {
	return string(t)
}

// IsValid checks if the token status is valid
func (t TokenStatus) IsValid() bool {
	switch t {
	case TokenStatusActive, TokenStatusUsed, TokenStatusRevoked, TokenStatusExpired:
		return true
	default:
		return false
	}
}

// ========================= GRACE PERIOD DATA =========================

// GracePeriodData represents cached token data during grace period
type GracePeriodData struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	JTI          string `json:"jti"`
	NewSessionID string `json:"new_session_id"`
	CreatedAt    int64  `json:"created_at"`
	ExpiresAt    int64  `json:"expires_at"`
}

// NewGracePeriodData creates a new GracePeriodData with timestamps
func NewGracePeriodData(accessToken, refreshToken, jti, newSessionID string) *GracePeriodData {
	now := time.Now()
	return &GracePeriodData{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		JTI:          jti,
		NewSessionID: newSessionID,
		CreatedAt:    now.Unix(),
		ExpiresAt:    now.Add(GracePeriodTTL).Unix(),
	}
}

// IsExpired checks if the grace period data has expired
func (g *GracePeriodData) IsExpired() bool {
	return time.Now().Unix() > g.ExpiresAt
}

// GracePeriodTTL is the TTL for grace period cache
const GracePeriodTTL = 10 * time.Second

// ========================= OUTBOX EVENT STATUS =========================

// OutboxEventStatus represents the status of an outbox event
type OutboxEventStatus string

const (
	OutboxEventStatusPending   OutboxEventStatus = "PENDING"
	OutboxEventStatusPublished OutboxEventStatus = "PUBLISHED"
	OutboxEventStatusFailed    OutboxEventStatus = "FAILED"
)

// String returns the string representation of OutboxEventStatus
func (o OutboxEventStatus) String() string {
	return string(o)
}

// IsValid checks if the outbox event status is valid
func (o OutboxEventStatus) IsValid() bool {
	switch o {
	case OutboxEventStatusPending, OutboxEventStatusPublished, OutboxEventStatusFailed:
		return true
	default:
		return false
	}
}

// ========================= REVOCATION TYPE =========================

// RevocationType represents why a session/token was revoked
type RevocationType string

const (
	RevocationTypeUserLogout     RevocationType = "USER_LOGOUT"
	RevocationTypeAdminLogout    RevocationType = "ADMIN_LOGOUT"
	RevocationTypeTokenTheft     RevocationType = "TOKEN_THEFT"
	RevocationTypePasswordChange RevocationType = "PASSWORD_CHANGE"
	RevocationTypeAccountBan     RevocationType = "ACCOUNT_BAN"
	RevocationTypeExpired       RevocationType = "EXPIRED"
)

// String returns the string representation of RevocationType
func (r RevocationType) String() string {
	return string(r)
}

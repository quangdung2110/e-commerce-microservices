package entity

import (
	"time"

	"github.com/google/uuid"
)

// OTPPurpose represents the purpose of OTP
type OTPPurpose string

const (
	OTPPurposeRegister      OTPPurpose = "REGISTER"
	OTPPurposeLogin         OTPPurpose = "LOGIN"
	OTPPurposeResetPassword OTPPurpose = "RESET_PASSWORD"
	OTPPurposeVerifyEmail   OTPPurpose = "VERIFY_EMAIL"
	OTPPurposeVerifyPhone   OTPPurpose = "VERIFY_PHONE"
	OTPPurposeEnable2FA     OTPPurpose = "ENABLE_2FA"
)

// OTPChannel represents the delivery channel for OTP
type OTPChannel string

const (
	OTPChannelEmail OTPChannel = "EMAIL"
	OTPChannelPhone OTPChannel = "PHONE"
)

// OTP represents an OTP verification record
// Stored in Redis with TTL
type OTP struct {
	RequestID      string     `json:"request_id"` // UUID for client to reference
	Purpose        OTPPurpose `json:"purpose"`
	Channel        OTPChannel `json:"channel"`
	CodeHash       string     `json:"code_hash"`         // Bcrypt hash of 6-digit code
	Identifier     string     `json:"identifier"`        // Email or phone number
	IdentifierHash string     `json:"identifier_hash"`   // SHA256(identifier) for Redis key
	UserID         *uuid.UUID `json:"user_id,omitempty"` // For existing users
	Attempts       int        `json:"attempts"`          // Failed verification attempts
	IPAddress      string     `json:"ip_address"`
	CreatedAt      time.Time  `json:"created_at"`
	ExpiresAt      time.Time  `json:"expires_at"` // depends on channel
}

// NewOTP creates a new OTP instance
func NewOTP(
	purpose OTPPurpose,
	channel OTPChannel,
	codeHash string,
	identifier string,
	identifierHash string,
	ipAddress string,
	ttl time.Duration,
) *OTP {
	now := time.Now().UTC()

	return &OTP{
		RequestID:      uuid.New().String(),
		Purpose:        purpose,
		Channel:        channel,
		CodeHash:       codeHash,
		Identifier:     identifier,
		IdentifierHash: identifierHash,
		Attempts:       0,
		IPAddress:      ipAddress,
		CreatedAt:      now,
		ExpiresAt:      now.Add(ttl),
	}
}

// IsExpired checks if OTP has expired
func (o *OTP) IsExpired() bool {
	return time.Now().UTC().After(o.ExpiresAt)
}

// IncrementAttempts increments failed verification attempts
func (o *OTP) IncrementAttempts() {
	o.Attempts++
}

// MaxAttemptsReached checks if maximum verification attempts exceeded
func (o *OTP) MaxAttemptsReached() bool {
	return o.Attempts > 3
}

// RedisKey generates Redis key for this OTP
func (o *OTP) RedisKey() string {
	return "otp:" + string(o.Channel) + ":" + string(o.Purpose) + ":" + o.IdentifierHash
}

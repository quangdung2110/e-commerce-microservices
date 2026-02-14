package entity

import (
	"time"

	"github.com/google/uuid"
)

/*
Explain all the scenarios where LoginAttempt would be used.
Scenario A: A user tries to log in using an OTP (One-Time Password) sent to their email / phone. If the OTP has expired, the login attempt should be marked as failed with the reason OTP_EXPIRED.
Scenario B: A user attempts to log in using a password. If the password is incorrect, the login attempt should be marked as failed with the reason WRONG_PASSWORD.
Scenario C: A user tries to log in with an email that does not exist in the system. The login attempt should be marked as failed with the reason USER_NOT_FOUND.
*/

// AttemptType represents the type of authentication attempt
type AttemptType string

const (
	AttemptTypePassword AttemptType = "PASSWORD"
	AttemptTypeOTP      AttemptType = "OTP"
	AttemptTypeOAuth    AttemptType = "OAUTH"
	AttemptType2FA      AttemptType = "TWO_FACTOR"
)

// FailureReason represents why a login attempt failed
type FailureReason string

const (
	FailureWrongPassword  FailureReason = "WRONG_PASSWORD"
	FailureAccountLocked  FailureReason = "ACCOUNT_LOCKED"
	FailureAccountDeleted FailureReason = "ACCOUNT_DELETED"
	FailureOTPExpired     FailureReason = "OTP_EXPIRED"
	FailureOTPInvalid     FailureReason = "OTP_INVALID"
	FailureRateLimited    FailureReason = "RATE_LIMITED"
	FailureIPBlocked      FailureReason = "IP_BLOCKED"
	Failure2FARequired    FailureReason = "TWO_FACTOR_REQUIRED"
	Failure2FAInvalid     FailureReason = "TWO_FACTOR_INVALID"
)

// LoginAttempt represents a login attempt record
// Mapped to PostgreSQL table: auth_login_attempts
type LoginAttempt struct {
	AttemptID       int64          `json:"attempt_id"`
	UserID          *uuid.UUID     `json:"user_id,omitempty"`
	LoginIdentifier string         `json:"login_identifier"` // Email/Phone/Username entered
	AttemptType     AttemptType    `json:"attempt_type"`
	IsSuccessful    bool           `json:"is_successful"`
	FailureReason   *FailureReason `json:"failure_reason,omitempty"`
	IPAddress       string         `json:"ip_address"`
	UserAgent       string         `json:"user_agent"`
	DeviceID        *string        `json:"device_id,omitempty"`
	IsSuspicious    bool           `json:"is_suspicious"`
	AttemptedAt     time.Time      `json:"attempted_at"`
}

// NewLoginAttempt creates a new login attempt record
func NewLoginAttempt(
	userID *uuid.UUID,
	loginIdentifier string,
	attemptType AttemptType,
	isSuccessful bool,
	ipAddress string,
	userAgent string,
) *LoginAttempt {
	return &LoginAttempt{
		UserID:          userID,
		LoginIdentifier: loginIdentifier,
		AttemptType:     attemptType,
		IsSuccessful:    isSuccessful,
		IPAddress:       ipAddress,
		UserAgent:       userAgent,
		IsSuspicious:    false,
		AttemptedAt:     time.Now().UTC(),
	}
}

// MarkAsSuspicious flags this attempt as suspicious
func (la *LoginAttempt) MarkAsSuspicious() {
	la.IsSuspicious = true
}

// SetFailureReason sets the reason for failure
func (la *LoginAttempt) SetFailureReason(reason FailureReason) {
	la.FailureReason = &reason
}

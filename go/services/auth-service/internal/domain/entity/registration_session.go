package entity

import "time"

// RegistrationSession represents a registration session store in Redis
type RegistrationSession struct {
	SessionID         string  `json:"session_id"`
	Email             *string `json:"email,omitempty"`
	EmailVerified     bool    `json:"email_verified"`
	PhoneNumber       *string `json:"phone_number,omitempty"`
	PhoneVerified     bool    `json:"phone_verified"`
	CurrentStep       int     `json:"current_step"` // 1-5
	EmailOTPRequestID *string `json:"email_otp_request_id,omitempty"`
	PhoneOTPRequestID *string `json:"phone_otp_request_id,omitempty"`
	CreatedAt         int64   `json:"created_at"`
	ExpiresAt         int64   `json:"expires_at"`
}

func (rs *RegistrationSession) IsExpired() bool {
	return rs.ExpiresAt < time.Now().UTC().Unix()
}

package dto

import "time"

// ========================= REQUEST DTOs =========================

type ForgotPasswordRequest struct {
	Email string `json:"email" validate:"required,email"`
}

// ResetPasswordRequest represents password reset request
type ResetPasswordRequest struct {
	Token       string `json:"token" validate:"required"`
	NewPassword string `json:"new_password" validate:"required,min=8"`
}

// ChangePasswordRequest represents password change request (authenticated)
type ChangePasswordRequest struct {
	OldPassword        string `json:"old_password" validate:"required"`
	NewPassword        string `json:"new_password" validate:"required,min=8"`
	KeepCurrentSession bool   `json:"keep_current_session"`
}

type ValidateResetTokenRequest struct {
	Token string `json:"token" validate:"required"`
}

// ========================= RESPONSE DTOs =========================

// ForgotPasswordResponse represents forgot password response
type ForgotPasswordResponse struct {
	Success     bool   `json:"success"`
	Message     string `json:"message"`
	EmailSent   bool   `json:"email_sent"`
	MaskedEmail string `json:"masked_email"` // "t***@example.com"
}

// ResetPasswordResponse represents password reset response
type ResetPasswordResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message"`
}

// ChangePasswordResponse represents password change response
type ChangePasswordResponse struct {
	Success              bool   `json:"success"`
	Message             string `json:"message"`
	SessionsRevokedCount int64  `json:"sessions_revoked_count"` // Number of other sessions revoked for security
}

// ValidateResetTokenResponse represents token validation response
type ValidateResetTokenResponse struct {
	Valid     bool      `json:"valid"`
	Message   string    `json:"message,omitempty"`
	ExpiresAt time.Time `json:"expires_at,omitempty"` // Seconds until expiration
}

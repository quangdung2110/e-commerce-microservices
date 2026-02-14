package entity

import (
	"time"

	"github.com/google/uuid"
)

// PasswordResetToken represents a password reset token
// Mapped to PostgreSQL table: auth_password_reset_tokens
type PasswordResetToken struct {
	TokenID            uuid.UUID  `json:"token_id"`
	UserID             uuid.UUID  `json:"user_id"`
	TokenHash          string     `json:"token_hash"` // SHA-256 hash
	IsUsed             bool       `json:"is_used"`
	UsedAt             *time.Time `json:"used_at,omitempty"`
	RequestedIP        string     `json:"requested_ip"`
	RequestedUserAgent string     `json:"requested_user_agent"`
	CreatedAt          time.Time  `json:"created_at"`
	ExpiresAt          time.Time  `json:"expires_at"`
}

// NewPasswordResetToken creates a new password reset token
func NewPasswordResetToken(
	userID uuid.UUID,
	tokenHash string,
	requestedIP string,
	requestedUserAgent string,
	ttl time.Duration,
) *PasswordResetToken {
	now := time.Now().UTC()

	return &PasswordResetToken{
		TokenID:            uuid.New(),
		UserID:             userID,
		TokenHash:          tokenHash,
		IsUsed:             false,
		RequestedIP:        requestedIP,
		RequestedUserAgent: requestedUserAgent,
		CreatedAt:          now,
		ExpiresAt:          now.Add(ttl),
	}
}

// IsExpired checks if token has expired
func (t *PasswordResetToken) IsExpired() bool {
	return time.Now().UTC().After(t.ExpiresAt)
}

// MarkAsUsed marks the token as used
func (t *PasswordResetToken) MarkAsUsed() {
	now := time.Now().UTC()
	t.IsUsed = true
	t.UsedAt = &now
}

// IsValid checks if token is usable
func (t *PasswordResetToken) IsValid() bool {
	return !t.IsUsed && !t.IsExpired()
}

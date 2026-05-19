package entity

import (
	"time"
)

type RefreshToken struct {
	ID        string
	UserID    string
	TokenHash string
	ExpiresAt time.Time
	CreatedAt time.Time
	RevokedAt *time.Time
	DeviceInfo string
	IPAddress  string
}

func NewRefreshToken(id, userID, tokenHash string, ttl time.Duration, deviceInfo, ipAddress string) *RefreshToken {
	return &RefreshToken{
		ID:         id,
		UserID:     userID,
		TokenHash:  tokenHash,
		ExpiresAt:  time.Now().Add(ttl),
		CreatedAt:  time.Now(),
		DeviceInfo: deviceInfo,
		IPAddress:  ipAddress,
	}
}

func (t *RefreshToken) IsExpired() bool {
	return time.Now().After(t.ExpiresAt)
}

func (t *RefreshToken) IsActive() bool {
	return t.RevokedAt == nil && !t.IsExpired()
}

func (t *RefreshToken) Revoke() {
	now := time.Now()
	t.RevokedAt = &now
}

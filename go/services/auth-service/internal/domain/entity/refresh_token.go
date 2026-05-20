package entity

import (
	"time"
)

type RefreshToken struct {
	ID         string
	UserID     string
	TokenHash  string
	FamilyID   string
	ReplacedBy *string
	DeviceID   string
	DeviceInfo string
	IPAddress  string
	ExpiresAt  time.Time
	RevokedAt  *time.Time
	CreatedAt  time.Time
}

func NewRefreshToken(id, userID, tokenHash, deviceID, deviceInfo, ipAddress string, ttl time.Duration) *RefreshToken {
	return &RefreshToken{
		ID:         id,
		UserID:     userID,
		TokenHash:  tokenHash,
		FamilyID:   generateFamilyID(),
		ReplacedBy: nil,
		DeviceID:   deviceID,
		DeviceInfo: deviceInfo,
		IPAddress:  ipAddress,
		ExpiresAt:  time.Now().Add(ttl),
		RevokedAt:  nil,
		CreatedAt:  time.Now(),
	}
}

func (t *RefreshToken) IsExpired() bool {
	return time.Now().After(t.ExpiresAt)
}

func (t *RefreshToken) IsActive() bool {
	return t.RevokedAt == nil && !t.IsExpired()
}

func (t *RefreshToken) IsRevoked() bool {
	return t.RevokedAt != nil
}

func (t *RefreshToken) Revoke() {
	now := time.Now()
	t.RevokedAt = &now
}

func (t *RefreshToken) WithinGracePeriod() bool {
	if t.RevokedAt == nil {
		return false
	}
	gracePeriod := 10 * time.Second
	return time.Since(*t.RevokedAt) <= gracePeriod
}

func (t *RefreshToken) GracePeriodExpiresAt() time.Time {
	if t.RevokedAt == nil {
		return time.Time{}
	}
	return t.RevokedAt.Add(10 * time.Second)
}

func (t *RefreshToken) MarkReplaced(newTokenID string) {
	t.ReplacedBy = &newTokenID
}

func generateFamilyID() string {
	return time.Now().Format("20060102150405") + "-" + randomString(8)
}

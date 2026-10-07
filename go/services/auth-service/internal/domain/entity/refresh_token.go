package entity

import (
	"time"

	"github.com/google/uuid"
)

// RefreshToken is one opaque credential inside a session family. Only the
// SHA-256 digest is persisted; the plaintext exists only in the cookie.
//
// FamilyID is the stable session identifier exposed as `session_id`; a rotation
// creates a new row in the same family (ADR-002).
type RefreshToken struct {
	ID                uuid.UUID
	AccountID         uuid.UUID
	FamilyID          uuid.UUID
	TokenHash         []byte
	DeviceID          *uuid.UUID
	DeviceName        string
	UserAgent         string
	IPAddress         string
	ExpiresAt         time.Time
	AbsoluteExpiresAt time.Time
	RevokedAt         *time.Time
	ReplacedBy        *uuid.UUID
	LastUsedAt        *time.Time
	CreatedAt         time.Time
}

// SessionContext carries the client attributes recorded with a session.
type SessionContext struct {
	DeviceName string
	UserAgent  string
	IPAddress  string
}

// NewSession starts a new session family for an account.
func NewSession(accountID uuid.UUID, tokenHash []byte, sliding, absolute time.Duration, ctx SessionContext, now time.Time) *RefreshToken {
	now = now.UTC()
	absoluteExpiry := now.Add(absolute)
	expiry := now.Add(sliding)
	if expiry.After(absoluteExpiry) {
		expiry = absoluteExpiry
	}
	return &RefreshToken{
		ID:                uuid.New(),
		AccountID:         accountID,
		FamilyID:          uuid.New(),
		TokenHash:         tokenHash,
		DeviceName:        ctx.DeviceName,
		UserAgent:         ctx.UserAgent,
		IPAddress:         ctx.IPAddress,
		ExpiresAt:         expiry,
		AbsoluteExpiresAt: absoluteExpiry,
		CreatedAt:         now,
	}
}

// Rotate builds the successor of this token inside the same family. The sliding
// expiry is extended but never beyond the absolute expiry of the session.
func (t *RefreshToken) Rotate(tokenHash []byte, sliding time.Duration, ctx SessionContext, now time.Time) *RefreshToken {
	now = now.UTC()
	expiry := now.Add(sliding)
	if expiry.After(t.AbsoluteExpiresAt) {
		expiry = t.AbsoluteExpiresAt
	}
	deviceName := ctx.DeviceName
	if deviceName == "" {
		deviceName = t.DeviceName
	}
	userAgent := ctx.UserAgent
	if userAgent == "" {
		userAgent = t.UserAgent
	}
	ipAddress := ctx.IPAddress
	if ipAddress == "" {
		ipAddress = t.IPAddress
	}
	return &RefreshToken{
		ID:                uuid.New(),
		AccountID:         t.AccountID,
		FamilyID:          t.FamilyID,
		TokenHash:         tokenHash,
		DeviceID:          t.DeviceID,
		DeviceName:        deviceName,
		UserAgent:         userAgent,
		IPAddress:         ipAddress,
		ExpiresAt:         expiry,
		AbsoluteExpiresAt: t.AbsoluteExpiresAt,
		CreatedAt:         now,
	}
}

// IsRevoked reports whether the token has already been consumed or revoked.
func (t *RefreshToken) IsRevoked() bool { return t.RevokedAt != nil }

// IsExpired reports whether either expiry has passed.
func (t *RefreshToken) IsExpired(now time.Time) bool {
	now = now.UTC()
	return !now.Before(t.ExpiresAt) || !now.Before(t.AbsoluteExpiresAt)
}

// IsUsable reports whether the token may still be exchanged (AUTH-BR-002).
// There is no grace period: a revoked or expired token is terminal.
func (t *RefreshToken) IsUsable(now time.Time) bool {
	return !t.IsRevoked() && !t.IsExpired(now)
}

// Session is the read model returned by the session listing. It is derived from
// the newest active token of a family and never exposes a token or its hash.
type Session struct {
	ID         uuid.UUID
	DeviceName string
	IPAddress  string
	CreatedAt  time.Time
	LastUsedAt time.Time
	ExpiresAt  time.Time
}

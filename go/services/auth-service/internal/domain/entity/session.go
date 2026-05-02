package entity

import (
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// DeviceType represents the type of device accessing the system
type DeviceType string

const (
	DeviceTypeMobile  DeviceType = "MOBILE"
	DeviceTypeDesktop DeviceType = "DESKTOP"
	DeviceTypeTablet  DeviceType = "TABLET"
	DeviceTypeUnknown DeviceType = "UNKNOWN"
)

// Session represents an active user session
// Mapped to PostgreSQL table: auth_sessions
type Session struct {
	SessionID uuid.UUID `json:"session_id"`
	UserID    uuid.UUID `json:"user_id"`

	// Device Fingerprint
	DeviceID   *string    `json:"device_id,omitempty"`
	DeviceName *string    `json:"device_name,omitempty"` // "iPhone 15 Pro", "Chrome on Windows"
	DeviceType DeviceType `json:"device_type"`
	IPAddress  string     `json:"ip_address"`
	UserAgent  string     `json:"user_agent"`

	// Geolocation
	GeoCountry *string `json:"geo_country,omitempty"` // ISO country code
	GeoCity    *string `json:"geo_city,omitempty"`

	// Session State
	IsActive bool `json:"is_active"`

	// Security
	RefreshTokenHash string `json:"refresh_token_hash"`

	// Token Rotation & Grace Period (Module 3)
	FamilyID       *uuid.UUID         `json:"family_id,omitempty"`        // Groups tokens from same login session
	RotatedFrom    *uuid.UUID         `json:"rotated_from,omitempty"`     // Parent session that rotated to this
	GraceExpiresAt *time.Time         `json:"grace_expires_at,omitempty"` // When grace period expires
	TokenStatus    types.TokenStatus  `json:"token_status"`               // ACTIVE, USED, REVOKED, EXPIRED

	// Timestamps
	CreatedAt    time.Time  `json:"created_at"`
	ExpiresAt    time.Time  `json:"expires_at"`
	RevokedAt    *time.Time `json:"revoked_at,omitempty"`
	LastActivity time.Time  `json:"last_activity"` // Updated via ActivityTracker middleware
}

// SessionStatus constants
type SessionStatus string

const (
	SessionStatusActive  SessionStatus = "ACTIVE"
	SessionStatusUsed   SessionStatus = "USED"
	SessionStatusRevoked SessionStatus = "REVOKED"
	SessionStatusExpired SessionStatus = "EXPIRED"
)

// NewSession creates a new session instance
func NewSession(userID uuid.UUID, deviceInfo DeviceInfo, geoLocation *GeoLocation, refreshTokenHash string, expiresAt time.Time) *Session {
	now := time.Now().UTC()

	return &Session{
		SessionID:        uuid.New(),
		UserID:           userID,
		DeviceID:         deviceInfo.DeviceID,
		DeviceName:       deviceInfo.DeviceName,
		DeviceType:       deviceInfo.DeviceType,
		IPAddress:        deviceInfo.IPAddress,
		UserAgent:        deviceInfo.UserAgent,
		GeoCountry:       geoLocation.Country,
		GeoCity:          geoLocation.City,
		IsActive:         true,
		RefreshTokenHash: refreshTokenHash,
		CreatedAt:        now,
		ExpiresAt:        expiresAt,
		LastActivity:     now,
	}
}

// UpdateActivity updates the last activity timestamp
func (s *Session) UpdateActivity() {
	s.LastActivity = time.Now().UTC()
}

// Revoke marks the session as inactive
func (s *Session) Revoke() {
	now := time.Now().UTC()
	s.IsActive = false
	s.RevokedAt = &now
}

// IsExpired checks if the session has expired
func (s *Session) IsExpired() bool {
	return time.Now().UTC().After(s.ExpiresAt)
}

// IsValid checks if session is active and not expired
func (s *Session) IsValid() bool {
	return s.IsActive && !s.IsExpired()
}

// IsRevoked checks if the session/token has been revoked
func (s *Session) IsRevoked() bool {
	return s.TokenStatus == types.TokenStatusRevoked
}

// IsInGracePeriod checks if the session is in its grace period (after being revoked)
func (s *Session) IsInGracePeriod() bool {
	if s.GraceExpiresAt == nil {
		return false
	}
	return time.Now().Before(*s.GraceExpiresAt)
}

// SetGracePeriod sets the grace period for the session
func (s *Session) SetGracePeriod(ttl time.Duration) {
	expires := time.Now().Add(ttl)
	s.GraceExpiresAt = &expires
}

// GetFamilyID returns the family ID, creating one if not set
func (s *Session) GetFamilyID() uuid.UUID {
	if s.FamilyID != nil {
		return *s.FamilyID
	}
	familyID := uuid.New()
	s.FamilyID = &familyID
	return familyID
}

// DeviceInfo contains device-related information
type DeviceInfo struct {
	DeviceID   *string
	DeviceName *string
	DeviceType DeviceType
	IPAddress  string
	UserAgent  string
}

type GeoLocation struct {
	Country   *string `json:"country"`
	City      *string `json:"city"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

// SessionWithLocation represents session with geolocation for impossible travel detection
type SessionWithLocation struct {
	SessionID    uuid.UUID    `json:"session_id"`
	CreatedAt    time.Time    `json:"created_at"`
	LastActivity time.Time    `json:"last_activity"`
	Location     *GeoLocation `json:"location,omitempty"`
}

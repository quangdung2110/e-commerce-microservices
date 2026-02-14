package entity

import (
	"time"

	"github.com/google/uuid"
)

// TokenType represents the type of token
type TokenType string

const (
	TokenTypeAccess  TokenType = "access"
	TokenTypeRefresh TokenType = "refresh"
)

// UserRole represents user roles in the system
type UserRole string

const (
	RoleCustomer          UserRole = "CUSTOMER"
	RoleSellerOwner       UserRole = "SELLER_OWNER"
	RoleSellerStaff       UserRole = "SELLER_STAFF"
	RolePlatformAdmin     UserRole = "PLATFORM_ADMIN"
	RolePlatformSupporter UserRole = "PLATFORM_SUPPORTER"
)

// AccountType represents account types
type AccountType string

const (
	AccountTypePersonal AccountType = "PERSONAL"
	AccountTypeManaged  AccountType = "MANAGED"
)

// RestrictionStatus represents user restriction status
type RestrictionStatus string

const (
	RestrictionNone      RestrictionStatus = "NONE"
	RestrictionWarning   RestrictionStatus = "WARNING"
	RestrictionLimited   RestrictionStatus = "LIMITED"
	RestrictionSuspended RestrictionStatus = "SUSPENDED"
)

// TokenClaims represents JWT token payload
type TokenClaims struct {
	// Standard JWT claims
	JTI       string    `json:"jti"` // Token ID
	SessionID uuid.UUID `json:"sid"` // Session ID in auth_sessions table
	Subject   uuid.UUID `json:"sub"` // User ID
	IssuedAt  int64     `json:"iat"`
	ExpiresAt int64     `json:"exp"`
	Issuer    string    `json:"iss"`
	Type      TokenType `json:"type"`

	// User Context (embedded to avoid DB lookup)
	Role              UserRole          `json:"role"`
	AccountType       AccountType       `json:"account_type"`
	ParentID          *uuid.UUID        `json:"parent_id,omitempty"` // For MANAGED accounts
	RestrictionStatus RestrictionStatus `json:"restriction_status"`

	// Seller Context (if applicable)
	SellerIDs      []uuid.UUID `json:"seller_ids,omitempty"`
	ActiveSellerID *uuid.UUID  `json:"active_seller_id,omitempty"`
}

// TokenPair represents access and refresh tokens
type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"` // Access token TTL in seconds
	TokenType    string `json:"token_type"` // "Bearer"
}

// NewTokenClaims creates new token claims
func NewTokenClaims(
	userID uuid.UUID,
	tokenType TokenType,
	role UserRole,
	accountType AccountType,
	restrictionStatus RestrictionStatus,
	sessionID uuid.UUID,

	ttl time.Duration,
) *TokenClaims {
	now := time.Now().UTC()

	return &TokenClaims{
		JTI:               uuid.New().String(),
		Subject:           userID,
		SessionID:         sessionID,
		IssuedAt:          now.Unix(),
		ExpiresAt:         now.Add(ttl).Unix(),
		Issuer:            "ecommerce-platform",
		Type:              tokenType,
		Role:              role,
		AccountType:       accountType,
		RestrictionStatus: restrictionStatus,
	}
}

// IsExpired checks if token has expired
func (tc *TokenClaims) IsExpired() bool {
	return time.Now().UTC().Unix() > tc.ExpiresAt
}

// HasRestriction checks if user has any restriction
func (tc *TokenClaims) HasRestriction() bool {
	return tc.RestrictionStatus != RestrictionNone
}

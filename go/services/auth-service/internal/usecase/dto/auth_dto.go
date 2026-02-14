package dto

import (
	"github.com/google/uuid"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// ========================= REQUEST DTOs =========================

// LoginRequest represents login credentials
type LoginRequest struct {
	Identifier string `json:"identifier" validate:"required"` // Email or Phone
	Password   string `json:"password" validate:"required,min=8"`
}

// RefreshTokenRequest represents refresh token request
type RefreshTokenRequest struct {
	RefreshToken string `json:"refresh_token" validate:"required"`
}

// LogoutRequest represents logout request
type LogoutRequest struct {
	SessionID uuid.UUID `json:"session_id,omitempty"` // If empty, logout current session
	JTI       string    `json:"-"`                    // JWT ID from context (not from request body)
	//TokenExpiry int64     `json:"-"`                    // Token expiry from context (not from request body)
}

// LogoutAllRequest represents logout from all devices
type LogoutAllRequest struct {
	// CurrentSessionID uuid.UUID `json:"-"`
	// JTI              string    `json:"-"`
}

// ========================= RESPONSE DTOs =========================

// LoginResponse represents successful login response
type LoginResponse struct {
	AccessToken        string              `json:"access_token"`
	RefreshToken       string              `json:"refresh_token"`
	ExpiresIn          int64               `json:"expires_in"`
	TokenType          string              `json:"token_type"`
	User               *common.UserDTO     `json:"user"`
	RestrictionWarning *RestrictionWarning `json:"restriction_warning,omitempty"`
}

// RefreshTokenResponse represents token refresh response
type RefreshTokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
	TokenType    string `json:"token_type"`
}

// RestrictionWarning represents user restriction warning
type RestrictionWarning struct {
	Status  entity.RestrictionStatus `json:"status"`
	Message string                   `json:"message"`
	Details string                   `json:"details,omitempty"`
}

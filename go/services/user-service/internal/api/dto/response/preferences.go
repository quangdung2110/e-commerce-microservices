package response

import (
	"time"

	"github.com/google/uuid"
)

// PreferencesResponse represents the response body for preferences endpoints
type PreferencesResponse struct {
	UserID           uuid.UUID `json:"user_id"`
	Language         string    `json:"language"`
	Currency         string    `json:"currency"`
	EmailEnabled     bool      `json:"email_enabled"`
	InAppEnabled     bool      `json:"in_app_enabled"`
	MarketingEnabled bool      `json:"marketing_enabled"`
	UpdatedAt        time.Time `json:"updated_at"`
}

// PreferencesResponseFromEntity converts a preferences entity to a response
func PreferencesResponseFromEntity(p interface {
	GetUserID() uuid.UUID
	GetLanguage() string
	GetCurrency() string
	IsEmailEnabled() bool
	IsInAppEnabled() bool
	IsMarketingEnabled() bool
	GetUpdatedAt() time.Time
}) *PreferencesResponse {
	return &PreferencesResponse{
		UserID:           p.GetUserID(),
		Language:         p.GetLanguage(),
		Currency:         p.GetCurrency(),
		EmailEnabled:     p.IsEmailEnabled(),
		InAppEnabled:     p.IsInAppEnabled(),
		MarketingEnabled: p.IsMarketingEnabled(),
		UpdatedAt:        p.GetUpdatedAt(),
	}
}

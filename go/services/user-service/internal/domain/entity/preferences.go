package entity

import (
	"errors"
	"time"

	"github.com/google/uuid"
)

var (
	ErrInvalidLanguage   = errors.New("invalid language code")
	ErrInvalidCurrency   = errors.New("invalid currency code")
	ErrPreferencesExists = errors.New("preferences already exist for user")
)

// Preferences represents user application preferences
type Preferences struct {
	UserID           uuid.UUID `json:"user_id" db:"user_id"`
	Language         string    `json:"language" db:"language"`
	Currency         string    `json:"currency" db:"currency"`
	EmailEnabled     bool      `json:"email_enabled" db:"email_enabled"`
	InAppEnabled     bool      `json:"in_app_enabled" db:"in_app_enabled"`
	MarketingEnabled bool      `json:"marketing_enabled" db:"marketing_enabled"`
	UpdatedAt        time.Time `json:"updated_at" db:"updated_at"`
}

// Default preferences constants
const (
	DefaultLanguage = "vi"
	DefaultCurrency = "VND"
)

// ValidLanguages contains valid language codes
var ValidLanguages = map[string]bool{
	"vi": true,
	"en": true,
	"zh": true,
	"ja": true,
	"ko": true,
}

// ValidCurrencies contains valid currency codes
var ValidCurrencies = map[string]bool{
	"VND": true,
	"USD": true,
	"EUR": true,
	"JPY": true,
	"CNY": true,
}

// NewPreferences creates a new Preferences entity with default values
func NewPreferences(userID uuid.UUID) *Preferences {
	return &Preferences{
		UserID:           userID,
		Language:         DefaultLanguage,
		Currency:         DefaultCurrency,
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: true,
	}
}

// Update updates preferences with validation
func (p *Preferences) Update(language, currency string, emailEnabled, inAppEnabled, marketingEnabled *bool) error {
	if language != "" {
		if !ValidLanguages[language] {
			return ErrInvalidLanguage
		}
		p.Language = language
	}

	if currency != "" {
		if !ValidCurrencies[currency] {
			return ErrInvalidCurrency
		}
		p.Currency = currency
	}

	if emailEnabled != nil {
		p.EmailEnabled = *emailEnabled
	}

	if inAppEnabled != nil {
		p.InAppEnabled = *inAppEnabled
	}

	if marketingEnabled != nil {
		p.MarketingEnabled = *marketingEnabled
	}

	return nil
}

// IsValidLanguage checks if a language code is valid
func IsValidLanguage(lang string) bool {
	return ValidLanguages[lang]
}

// IsValidCurrency checks if a currency code is valid
func IsValidCurrency(currency string) bool {
	return ValidCurrencies[currency]
}

// GetUserID returns the user ID
func (p *Preferences) GetUserID() uuid.UUID {
	return p.UserID
}

// GetLanguage returns the language
func (p *Preferences) GetLanguage() string {
	return p.Language
}

// GetCurrency returns the currency
func (p *Preferences) GetCurrency() string {
	return p.Currency
}

// IsEmailEnabled returns email notification enabled status
func (p *Preferences) IsEmailEnabled() bool {
	return p.EmailEnabled
}

// IsInAppEnabled returns in-app notification enabled status
func (p *Preferences) IsInAppEnabled() bool {
	return p.InAppEnabled
}

// IsMarketingEnabled returns marketing notification enabled status
func (p *Preferences) IsMarketingEnabled() bool {
	return p.MarketingEnabled
}

// GetUpdatedAt returns the updated timestamp
func (p *Preferences) GetUpdatedAt() time.Time {
	return p.UpdatedAt
}

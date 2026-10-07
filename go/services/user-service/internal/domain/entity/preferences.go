package entity

import (
	"time"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// Preferences is the 1:1 companion of a profile. Only the three notification
// flags are writable; language and currency are fixed by the contract and are
// the fields Notification Service replicates.
type Preferences struct {
	UserID           uuid.UUID
	Language         vo.Language
	Currency         vo.Currency
	EmailEnabled     bool
	InAppEnabled     bool
	MarketingEnabled bool
	UpdatedAt        time.Time
}

// NewDefaultPreferences creates the opted-in defaults written at bootstrap
// (WF-USER-001).
func NewDefaultPreferences(userID uuid.UUID, now time.Time) *Preferences {
	return &Preferences{
		UserID:           userID,
		Language:         vo.LanguageVietnamese,
		Currency:         vo.CurrencyVND,
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: true,
		UpdatedAt:        now.UTC(),
	}
}

// NotificationChanges is the full replacement payload of PUT /preferences.
type NotificationChanges struct {
	EmailEnabled     bool
	InAppEnabled     bool
	MarketingEnabled bool
}

// Replace applies the notification flags and reports whether anything changed,
// so resending the same body neither writes nor emits a second event.
func (p *Preferences) Replace(changes NotificationChanges, now time.Time) bool {
	if p.EmailEnabled == changes.EmailEnabled &&
		p.InAppEnabled == changes.InAppEnabled &&
		p.MarketingEnabled == changes.MarketingEnabled {
		return false
	}

	p.EmailEnabled = changes.EmailEnabled
	p.InAppEnabled = changes.InAppEnabled
	p.MarketingEnabled = changes.MarketingEnabled
	p.UpdatedAt = now.UTC()
	return true
}

// EnsureSupported guards the values read back from storage. A row that somehow
// carries an unsupported language or currency is a defect, not client input.
func (p *Preferences) EnsureSupported() error {
	if !p.Language.Valid() || !p.Currency.Valid() {
		return apperr.ErrInvalidPreference.WithDetail("Preferences carry an unsupported language or currency.")
	}
	return nil
}

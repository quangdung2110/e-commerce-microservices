package entity

import (
	"time"

	"github.com/google/uuid"
)

// Preference is the local replica of t.user.events preferences-updated
// (09-data-model.md §6). Version is the event updated_at in epoch ms and is
// applied compare-and-set (05-ordering.md §6).
type Preference struct {
	UserID           uuid.UUID
	EmailEnabled     bool
	InAppEnabled     bool
	MarketingEnabled bool
	Timezone         *string // nil → default timezone
	Locale           *string // nil → vi
	Version          int64
	SyncedAt         time.Time
}

// DefaultPreference is used when no row exists yet (08-decide.md §5).
func DefaultPreference(userID uuid.UUID) Preference {
	return Preference{
		UserID:           userID,
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: false,
	}
}

// TimezoneOr returns the user's timezone or the fallback.
func (p Preference) TimezoneOr(fallback string) string {
	if p.Timezone != nil && *p.Timezone != "" {
		return *p.Timezone
	}
	return fallback
}

// LocaleOr returns the user's locale or the fallback.
func (p Preference) LocaleOr(fallback string) string {
	if p.Locale != nil && *p.Locale != "" {
		return *p.Locale
	}
	return fallback
}

// SuppressionReason classifies a suppression row.
type SuppressionReason string

const (
	SuppressionHardBounce SuppressionReason = "HARD_BOUNCE"
	SuppressionComplaint  SuppressionReason = "COMPLAINT"
	SuppressionSoftBounce SuppressionReason = "SOFT_BOUNCE"
	SuppressionManual     SuppressionReason = "MANUAL"
)

// Removable reports whether ops may delete this reason through the API
// (HARD_BOUNCE and COMPLAINT are not, D-14).
func (r SuppressionReason) Removable() bool {
	return r == SuppressionSoftBounce || r == SuppressionManual
}

// Suppression is an address that must not be mailed, keyed by user_id for
// Decide and by email_hash for the worker check (08-decide.md §4).
type Suppression struct {
	ID              uuid.UUID
	UserID          *uuid.UUID
	EmailHash       string
	Reason          SuppressionReason
	Source          string // ses | ops
	Until           *time.Time
	EvidenceEventID *int64
	SupersededAt    *time.Time
	CreatedAt       time.Time
}

// ActiveAt reports whether the suppression still applies.
func (s Suppression) ActiveAt(now time.Time) bool {
	if s.SupersededAt != nil {
		return false
	}
	return s.Until == nil || s.Until.After(now)
}

// Package entity holds the User aggregates. UserProfile is the aggregate root
// of the customer profile; addresses and preferences belong to it, while the
// retention record outlives it on purpose (docs/user/domain.md §2).
package entity

import (
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// Profile is the demographic data User Service owns. Its identity is the
// `user_id` Auth published, never a locally generated one (USER-INV-006).
type Profile struct {
	ID          uuid.UUID
	FirstName   *string
	LastName    *string
	DateOfBirth *time.Time
	Gender      vo.Gender
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

// NewProfile creates the empty profile of a verified account. Every field the
// owner has not filled in yet stays null (WF-USER-001).
func NewProfile(userID uuid.UUID, now time.Time) *Profile {
	now = now.UTC()
	return &Profile{
		ID:        userID,
		Gender:    vo.GenderUnknown,
		CreatedAt: now,
		UpdatedAt: now,
	}
}

// ProfileChanges is one JSON Merge Patch applied to a profile. A `Set` flag
// distinguishes "field absent from the patch" from "field explicitly null",
// which merge-patch semantics give different meanings.
type ProfileChanges struct {
	SetFirstName bool
	FirstName    *string

	SetLastName bool
	LastName    *string

	SetDateOfBirth bool
	DateOfBirth    *time.Time

	SetGender bool
	Gender    vo.Gender
}

// IsEmpty reports whether the patch would change nothing at all. The contract
// requires at least one property.
func (c ProfileChanges) IsEmpty() bool {
	return !c.SetFirstName && !c.SetLastName && !c.SetDateOfBirth && !c.SetGender
}

// Apply validates and applies a merge patch. It reports whether the profile
// actually changed, so an unchanged PATCH neither writes nor publishes an event.
func (p *Profile) Apply(changes ProfileChanges, now time.Time) (bool, error) {
	invalid := apperr.ErrInvalidProfileField

	firstName := p.FirstName
	if changes.SetFirstName {
		normalized, err := normalizeName(changes.FirstName, "first_name")
		if err != nil {
			invalid = invalid.WithFields(err.Fields...)
		} else {
			firstName = normalized
		}
	}

	lastName := p.LastName
	if changes.SetLastName {
		normalized, err := normalizeName(changes.LastName, "last_name")
		if err != nil {
			invalid = invalid.WithFields(err.Fields...)
		} else {
			lastName = normalized
		}
	}

	dateOfBirth := p.DateOfBirth
	if changes.SetDateOfBirth {
		if changes.DateOfBirth == nil {
			dateOfBirth = nil
		} else {
			day := changes.DateOfBirth.UTC().Truncate(24 * time.Hour)
			// A birth date in the future is impossible, so it is rejected
			// rather than stored (docs/user/data.md §3).
			if day.After(now.UTC()) {
				invalid = invalid.WithFields(apperr.FieldError{
					Field:   "date_of_birth",
					Message: "must not be in the future",
				})
			} else {
				dateOfBirth = &day
			}
		}
	}

	gender := p.Gender
	if changes.SetGender {
		if !changes.Gender.Valid() {
			invalid = invalid.WithFields(apperr.FieldError{
				Field:   "gender",
				Message: "must be MALE, FEMALE, OTHER or UNKNOWN",
			})
		} else {
			gender = changes.Gender
		}
	}

	if invalid.HasFields() {
		return false, invalid
	}

	if sameName(p.FirstName, firstName) && sameName(p.LastName, lastName) &&
		sameDate(p.DateOfBirth, dateOfBirth) && p.Gender == gender {
		return false, nil
	}

	p.FirstName = firstName
	p.LastName = lastName
	p.DateOfBirth = dateOfBirth
	p.Gender = gender
	p.UpdatedAt = now.UTC()
	return true, nil
}

// DisplayName is the read-only name the API returns and the `sender_name` Chat
// receives. It is derived, never stored, and falls back to a neutral label so
// the contract's non-empty constraint always holds.
func (p *Profile) DisplayName() string {
	parts := make([]string, 0, 2)
	if p.FirstName != nil && strings.TrimSpace(*p.FirstName) != "" {
		parts = append(parts, strings.TrimSpace(*p.FirstName))
	}
	if p.LastName != nil && strings.TrimSpace(*p.LastName) != "" {
		parts = append(parts, strings.TrimSpace(*p.LastName))
	}

	name := strings.Join(parts, " ")
	if name == "" {
		return constants.DisplayNameFallback
	}
	if len([]rune(name)) > constants.MaxDisplayNameLength {
		return string([]rune(name)[:constants.MaxDisplayNameLength])
	}
	return name
}

// Anonymize strips the PII of a deleted account (USER-INV-005). The row stays
// until the retention worker removes it, so downstream foreign keys and the
// deletion record keep a stable identity in the meantime.
func (p *Profile) Anonymize(now time.Time) {
	p.FirstName = nil
	p.LastName = nil
	p.DateOfBirth = nil
	p.Gender = vo.GenderUnknown
	p.UpdatedAt = now.UTC()
}

// IsAnonymized reports whether the profile still carries identifying data. It
// is a defensive read used by the consumer, not a stored state flag.
func (p *Profile) IsAnonymized() bool {
	return p.FirstName == nil && p.LastName == nil && p.DateOfBirth == nil &&
		p.Gender == vo.GenderUnknown
}

// normalizeName trims a nullable short name and enforces the contract bounds.
func normalizeName(value *string, field string) (*string, *apperr.Error) {
	if value == nil {
		return nil, nil
	}
	trimmed := strings.TrimSpace(*value)
	if trimmed == "" {
		// The schema uses minLength 1: clearing a name is done with null.
		return nil, apperr.ErrInvalidProfileField.WithFields(apperr.FieldError{
			Field:   field,
			Message: "must not be blank; send null to clear it",
		})
	}
	if len([]rune(trimmed)) > constants.MaxNameLength {
		return nil, apperr.ErrInvalidProfileField.WithFields(apperr.FieldError{
			Field:   field,
			Message: "must be at most 100 characters",
		})
	}
	return &trimmed, nil
}

func sameName(a, b *string) bool {
	switch {
	case a == nil && b == nil:
		return true
	case a == nil || b == nil:
		return false
	default:
		return *a == *b
	}
}

func sameDate(a, b *time.Time) bool {
	switch {
	case a == nil && b == nil:
		return true
	case a == nil || b == nil:
		return false
	default:
		return a.Equal(*b)
	}
}

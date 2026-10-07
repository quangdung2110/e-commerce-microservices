// Package request holds the wire shapes of the incoming payloads defined by
// contracts/http/user/v1/openapi.yaml. They translate into domain commands and
// carry no business rule of their own.
package request

import (
	"bytes"
	"encoding/json"
	"time"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// dateLayout is the `format: date` representation of date_of_birth.
const dateLayout = "2006-01-02"

// jsonNull is what a merge patch sends to remove a member (RFC 7396).
var jsonNull = []byte("null")

// OptionalString is a merge-patch member: absent, a string, or an explicit
// null. The three cases mean different things, so a plain *string is not enough.
type OptionalString struct {
	Present bool
	Value   *string
}

// UnmarshalJSON is called only when the member is present in the body, which is
// what makes Present meaningful.
func (o *OptionalString) UnmarshalJSON(data []byte) error {
	o.Present = true
	if bytes.Equal(data, jsonNull) {
		o.Value = nil
		return nil
	}

	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return apperr.ErrValidation.WithDetail("A profile field must be a string or null.")
	}
	o.Value = &value
	return nil
}

// OptionalDate is a merge-patch member holding a calendar date.
type OptionalDate struct {
	Present bool
	Value   *time.Time
}

// UnmarshalJSON accepts only the `format: date` shape of the contract, so a
// full timestamp is rejected instead of being silently truncated.
func (o *OptionalDate) UnmarshalJSON(data []byte) error {
	o.Present = true
	if bytes.Equal(data, jsonNull) {
		o.Value = nil
		return nil
	}

	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return apperr.ErrValidation.WithDetail("date_of_birth must be a string in YYYY-MM-DD form or null.")
	}
	parsed, err := time.Parse(dateLayout, value)
	if err != nil {
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "date_of_birth",
			Message: "must be a date in YYYY-MM-DD form",
		})
	}
	utc := parsed.UTC()
	o.Value = &utc
	return nil
}

// ProfilePatch is the body of PATCH /api/v1/users/me/profile. `display_name` is
// read-only and is rejected by the strict decoder if a client sends it.
type ProfilePatch struct {
	FirstName   OptionalString `json:"first_name"`
	LastName    OptionalString `json:"last_name"`
	DateOfBirth OptionalDate   `json:"date_of_birth"`
	Gender      OptionalString `json:"gender"`
}

// ToChanges converts the patch into the domain command, rejecting the two
// shapes the schema forbids: an empty patch and a null gender.
func (p ProfilePatch) ToChanges() (entity.ProfileChanges, error) {
	changes := entity.ProfileChanges{
		SetFirstName:   p.FirstName.Present,
		FirstName:      p.FirstName.Value,
		SetLastName:    p.LastName.Present,
		LastName:       p.LastName.Value,
		SetDateOfBirth: p.DateOfBirth.Present,
		DateOfBirth:    p.DateOfBirth.Value,
		SetGender:      p.Gender.Present,
	}

	if p.Gender.Present {
		if p.Gender.Value == nil {
			// `gender` is not nullable: UNKNOWN is the way to say "not stated".
			return entity.ProfileChanges{}, apperr.ErrInvalidProfileField.WithFields(apperr.FieldError{
				Field:   "gender",
				Message: "must not be null; use UNKNOWN instead",
			})
		}
		changes.Gender = vo.Gender(*p.Gender.Value)
	}

	if changes.IsEmpty() {
		return entity.ProfileChanges{}, apperr.ErrValidation.WithDetail("The patch must contain at least one field.")
	}
	return changes, nil
}

// AddressWrite is the body of POST and PUT on an address. Every field is
// required: PUT replaces the whole resource.
type AddressWrite struct {
	RecipientName  *string `json:"recipient_name"`
	RecipientPhone *string `json:"recipient_phone"`
	AddressLine    *string `json:"address_line"`
	ProvinceCode   *string `json:"province_code"`
	DistrictCode   *string `json:"district_code"`
	WardCode       *string `json:"ward_code"`
}

// ToInput checks that every required member is present and hands the values to
// the domain, which owns the format rules.
func (a AddressWrite) ToInput() (entity.AddressInput, error) {
	missing := apperr.ErrValidation.WithDetail("One or more required address fields are missing.")

	required := []struct {
		field string
		value *string
	}{
		{"recipient_name", a.RecipientName},
		{"recipient_phone", a.RecipientPhone},
		{"address_line", a.AddressLine},
		{"province_code", a.ProvinceCode},
		{"district_code", a.DistrictCode},
		{"ward_code", a.WardCode},
	}
	for _, item := range required {
		if item.value == nil {
			missing = missing.WithFields(apperr.FieldError{Field: item.field, Message: "is required"})
		}
	}
	if missing.HasFields() {
		return entity.AddressInput{}, missing
	}

	return entity.AddressInput{
		RecipientName:  *a.RecipientName,
		RecipientPhone: *a.RecipientPhone,
		AddressLine:    *a.AddressLine,
		ProvinceCode:   *a.ProvinceCode,
		DistrictCode:   *a.DistrictCode,
		WardCode:       *a.WardCode,
	}, nil
}

// NotificationPreferencesUpdate is the body of PUT /preferences. `language` and
// `currency` are read-only, so sending them is rejected by the strict decoder.
type NotificationPreferencesUpdate struct {
	EmailEnabled     *bool `json:"email_enabled"`
	InAppEnabled     *bool `json:"in_app_enabled"`
	MarketingEnabled *bool `json:"marketing_enabled"`
}

// ToChanges checks that all three flags were sent: the operation replaces the
// notification preferences as a whole.
func (p NotificationPreferencesUpdate) ToChanges() (entity.NotificationChanges, error) {
	missing := apperr.ErrValidation.WithDetail("One or more required preference fields are missing.")

	required := []struct {
		field string
		value *bool
	}{
		{"email_enabled", p.EmailEnabled},
		{"in_app_enabled", p.InAppEnabled},
		{"marketing_enabled", p.MarketingEnabled},
	}
	for _, item := range required {
		if item.value == nil {
			missing = missing.WithFields(apperr.FieldError{Field: item.field, Message: "is required"})
		}
	}
	if missing.HasFields() {
		return entity.NotificationChanges{}, missing
	}

	return entity.NotificationChanges{
		EmailEnabled:     *p.EmailEnabled,
		InAppEnabled:     *p.InAppEnabled,
		MarketingEnabled: *p.MarketingEnabled,
	}, nil
}

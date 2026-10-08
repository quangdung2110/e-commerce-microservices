package entity

import (
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// Address is a shipping address inside the address book of one profile. It only
// has meaning together with its owner, which is why every lookup is keyed on
// (address_id, user_id) (USER-BR-001).
type Address struct {
	ID             uuid.UUID
	UserID         uuid.UUID
	IsDefault      bool
	RecipientName  string
	RecipientPhone vo.Phone
	AddressLine    string
	ProvinceCode   string
	DistrictCode   string
	WardCode       string
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

// AddressInput is the validated write payload shared by create and replace.
type AddressInput struct {
	RecipientName  string
	RecipientPhone string
	AddressLine    string
	ProvinceCode   string
	DistrictCode   string
	WardCode       string
}

// validatedAddress is the input after normalization.
type validatedAddress struct {
	recipientName  string
	recipientPhone vo.Phone
	addressLine    string
	provinceCode   string
	districtCode   string
	wardCode       string
}

// Validate normalizes and checks every field against the OpenAPI schema. All
// violations are collected so the caller sees one complete 422 response.
func (in AddressInput) Validate() (validatedAddress, error) {
	invalid := apperr.ErrInvalidAddress
	out := validatedAddress{}

	out.recipientName = strings.TrimSpace(in.RecipientName)
	if out.recipientName == "" || len([]rune(out.recipientName)) > constants.MaxRecipientNameLength {
		invalid = invalid.WithFields(apperr.FieldError{
			Field:   "recipient_name",
			Message: "must be between 1 and 200 characters",
		})
	}

	phone, ok := vo.NewPhone(in.RecipientPhone)
	if !ok {
		invalid = invalid.WithFields(apperr.FieldError{
			Field:   "recipient_phone",
			Message: "must be an E.164 number, for example +84901234567",
		})
	}
	out.recipientPhone = phone

	out.addressLine = strings.TrimSpace(in.AddressLine)
	if out.addressLine == "" || len([]rune(out.addressLine)) > constants.MaxAddressLineLength {
		invalid = invalid.WithFields(apperr.FieldError{
			Field:   "address_line",
			Message: "must be between 1 and 500 characters",
		})
	}

	codes := []struct {
		field string
		value string
		out   *string
	}{
		{"province_code", in.ProvinceCode, &out.provinceCode},
		{"district_code", in.DistrictCode, &out.districtCode},
		{"ward_code", in.WardCode, &out.wardCode},
	}
	for _, code := range codes {
		trimmed := strings.TrimSpace(code.value)
		if !vo.ValidAreaCode(trimmed) {
			invalid = invalid.WithFields(apperr.FieldError{
				Field:   code.field,
				Message: "must be a Vietnamese administrative code of at most 20 characters",
			})
			continue
		}
		*code.out = trimmed
	}

	if invalid.HasFields() {
		return validatedAddress{}, invalid
	}
	return out, nil
}

// NewAddress creates an address for a profile. isDefault is decided by the
// application layer: the first address of a profile becomes the default
// (USER-BR-002).
func NewAddress(userID uuid.UUID, in AddressInput, isDefault bool, now time.Time) (*Address, error) {
	valid, err := in.Validate()
	if err != nil {
		return nil, err
	}

	now = now.UTC()
	return &Address{
		ID:             uuid.New(),
		UserID:         userID,
		IsDefault:      isDefault,
		RecipientName:  valid.recipientName,
		RecipientPhone: valid.recipientPhone,
		AddressLine:    valid.addressLine,
		ProvinceCode:   valid.provinceCode,
		DistrictCode:   valid.districtCode,
		WardCode:       valid.wardCode,
		CreatedAt:      now,
		UpdatedAt:      now,
	}, nil
}

// Replace applies a full update. The default flag is not part of the payload:
// it only changes through the dedicated set-default operation.
func (a *Address) Replace(in AddressInput, now time.Time) error {
	valid, err := in.Validate()
	if err != nil {
		return err
	}

	a.RecipientName = valid.recipientName
	a.RecipientPhone = valid.recipientPhone
	a.AddressLine = valid.addressLine
	a.ProvinceCode = valid.provinceCode
	a.DistrictCode = valid.districtCode
	a.WardCode = valid.wardCode
	a.UpdatedAt = now.UTC()
	return nil
}

// EnsureDeletable refuses to remove the address a checkout would pick by
// default while the book holds others (USER-BR-004): the owner promotes another
// address first. addressesInBook counts every address of the owner, this one
// included. The only address left is deletable even when it is the default,
// because no replacement exists and refusing would make the book impossible to
// empty again.
func (a *Address) EnsureDeletable(addressesInBook int) error {
	if a.IsDefault && addressesInBook > 1 {
		return apperr.ErrDefaultAddressCannotBeDeleted
	}
	return nil
}

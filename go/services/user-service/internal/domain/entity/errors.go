package entity

import "errors"

var (
	// Validation errors
	ErrInvalidGender          = errors.New("invalid gender value")
	ErrDateOfBirthInFuture    = errors.New("date of birth cannot be in the future")
	ErrRecipientNameRequired   = errors.New("recipient name is required")
	ErrAddressLineRequired    = errors.New("address line is required")
	ErrLocationRequired       = errors.New("ward, district, and city IDs are required")
	ErrPhoneNumberInvalid    = errors.New("phone number format is invalid")
)

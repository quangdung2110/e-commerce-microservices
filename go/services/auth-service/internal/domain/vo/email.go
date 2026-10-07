package vo

import (
	"net/mail"
	"strings"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
)

// MaxEmailLength matches the VARCHAR(254) columns and the OpenAPI Email schema.
const MaxEmailLength = 254

// Email is a validated address plus the normalized form used for the unique
// identity constraint. The schema enforces email_normalized = lower(btrim(email)).
type Email struct {
	address    string
	normalized string
}

// NewEmail validates and normalizes a client-supplied address.
func NewEmail(raw string) (Email, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return Email{}, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "email",
			Message: "must not be empty",
		})
	}
	if len(trimmed) > MaxEmailLength {
		return Email{}, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "email",
			Message: "must be at most 254 characters",
		})
	}
	parsed, err := mail.ParseAddress(trimmed)
	if err != nil || parsed.Address != trimmed || strings.Count(trimmed, "@") != 1 {
		return Email{}, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "email",
			Message: "must be a valid email address",
		})
	}
	at := strings.Index(trimmed, "@")
	if at < 1 || at == len(trimmed)-1 {
		return Email{}, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "email",
			Message: "must be a valid email address",
		})
	}
	return Email{address: trimmed, normalized: strings.ToLower(trimmed)}, nil
}

// EmailFromStorage rebuilds the value object from a persisted row without
// re-running client-facing validation.
func EmailFromStorage(address, normalized string) Email {
	return Email{address: address, normalized: normalized}
}

// String returns the address as the account supplied it.
func (e Email) String() string { return e.address }

// Normalized returns the unique lookup key.
func (e Email) Normalized() string { return e.normalized }

// IsZero reports whether the account has no email, which is the case only after
// a GDPR anonymization.
func (e Email) IsZero() bool { return e.address == "" && e.normalized == "" }

// Equal compares two addresses by their normalized form.
func (e Email) Equal(other Email) bool { return e.normalized == other.normalized }

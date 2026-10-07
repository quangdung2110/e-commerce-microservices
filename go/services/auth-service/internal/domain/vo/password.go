package vo

import (
	"strings"
	"unicode/utf8"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
)

// PasswordPolicy carries the configured password rules. The public contract
// fixes the length bounds and deliberately leaves anything stronger to the
// implementation, so this policy stays small and predictable: no character-class
// requirement, whitespace allowed.
type PasswordPolicy struct {
	MinLength int
	MaxLength int
}

// NewPasswordPolicy applies the contract bounds when a value is missing.
func NewPasswordPolicy(minLength, maxLength int) PasswordPolicy {
	if minLength <= 0 {
		minLength = 12
	}
	if maxLength <= 0 {
		maxLength = 128
	}
	return PasswordPolicy{MinLength: minLength, MaxLength: maxLength}
}

// ValidateFormat enforces the length bounds of the OpenAPI Password schema.
// A violation is a request-schema violation, so it maps to 400.
func (p PasswordPolicy) ValidateFormat(field, password string) error {
	length := utf8.RuneCountInString(password)
	switch {
	case length < p.MinLength:
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   field,
			Message: "must be at least 12 characters",
		})
	case length > p.MaxLength:
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   field,
			Message: "must be at most 128 characters",
		})
	}
	return nil
}

// ValidateStrength enforces the semantic rules that go beyond the schema. These
// map to 422 PASSWORD_POLICY_VIOLATION because the payload itself is well formed.
func (p PasswordPolicy) ValidateStrength(password string, email Email) error {
	lowered := strings.ToLower(password)

	if !email.IsZero() {
		local := email.Normalized()
		if at := strings.Index(local, "@"); at > 0 {
			local = local[:at]
		}
		if len(local) >= 3 && strings.Contains(lowered, local) {
			return apperr.ErrPasswordPolicy.WithDetail("The password must not contain the email address.")
		}
	}
	if isSingleRepeatedRune(password) {
		return apperr.ErrPasswordPolicy.WithDetail("The password must not repeat a single character.")
	}
	return nil
}

func isSingleRepeatedRune(password string) bool {
	if password == "" {
		return false
	}
	first, size := utf8.DecodeRuneInString(password)
	for _, r := range password[size:] {
		if r != first {
			return false
		}
	}
	return true
}

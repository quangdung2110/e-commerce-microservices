// Package pii holds the types that carry personal data through the process so
// they cannot leak into logs by accident (docs/notification/11-privacy.md §3).
package pii

import (
	"crypto/sha256"
	"encoding/hex"
	"net/mail"
	"strings"
)

// Email is a recipient address. It implements fmt.Stringer with a redacted
// value so %v, %s and structured loggers never print the address; use Raw()
// only at the provider boundary.
type Email struct {
	value string
}

// NewEmail validates and wraps an address.
func NewEmail(raw string) (Email, error) {
	trimmed := strings.TrimSpace(raw)
	if _, err := mail.ParseAddress(trimmed); err != nil {
		return Email{}, err
	}
	return Email{value: trimmed}, nil
}

// MustEmail wraps without validation (tests, fixtures).
func MustEmail(raw string) Email { return Email{value: strings.TrimSpace(raw)} }

// Raw returns the address for the provider call.
func (e Email) Raw() string { return e.value }

// IsZero reports an unset address.
func (e Email) IsZero() bool { return e.value == "" }

// String is redacted on purpose.
func (e Email) String() string { return "<redacted>" }

// GoString is redacted on purpose (%#v).
func (e Email) GoString() string { return "pii.Email{<redacted>}" }

// MarshalJSON is redacted so accidental serialisation cannot leak the address.
func (e Email) MarshalJSON() ([]byte, error) { return []byte(`"<redacted>"`), nil }

// Hash returns sha256(lower(trim(email))) hex, unsalted so a bounce today can be
// matched against a suppression written months ago (D-16).
func (e Email) Hash() string { return HashEmail(e.value) }

// HashEmail hashes a raw address the same way Email.Hash does.
func HashEmail(raw string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(raw))))
	return hex.EncodeToString(sum[:])
}

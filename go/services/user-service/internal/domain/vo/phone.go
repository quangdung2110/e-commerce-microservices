package vo

import (
	"errors"
	"regexp"
)

var (
	ErrPhoneNumberEmpty    = errors.New("phone number cannot be empty")
	ErrPhoneNumberInvalid  = errors.New("phone number format is invalid: must match pattern +?[0-9]{9,15}")
)

// PhoneNumber represents a validated phone number value object
type PhoneNumber struct {
	Value string
}

// phoneNumberRegex validates phone number format: optional + followed by 9-15 digits
var phoneNumberRegex = regexp.MustCompile(`^\+?[0-9]{9,15}$`)

// NewPhoneNumber creates a new PhoneNumber value object with validation
func NewPhoneNumber(value string) (PhoneNumber, error) {
	if value == "" {
		return PhoneNumber{}, ErrPhoneNumberEmpty
	}

	value = trimSpaces(value)

	if !phoneNumberRegex.MatchString(value) {
		return PhoneNumber{}, ErrPhoneNumberInvalid
	}

	return PhoneNumber{Value: value}, nil
}

// MustNewPhoneNumber creates a PhoneNumber and panics on error
func MustNewPhoneNumber(value string) PhoneNumber {
	p, err := NewPhoneNumber(value)
	if err != nil {
		panic(err)
	}
	return p
}

// trimSpaces removes leading and trailing spaces
func trimSpaces(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t') {
		s = s[1:]
	}
	for len(s) > 0 && (s[len(s)-1] == ' ' || s[len(s)-1] == '\t') {
		s = s[:len(s)-1]
	}
	return s
}

// String returns the phone number as a string
func (p PhoneNumber) String() string {
	return p.Value
}

// IsEmpty checks if the phone number is empty
func (p PhoneNumber) IsEmpty() bool {
	return p.Value == ""
}

// Equal compares two PhoneNumber values
func (p PhoneNumber) Equal(other PhoneNumber) bool {
	return p.Value == other.Value
}

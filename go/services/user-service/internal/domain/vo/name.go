package vo

import (
	"errors"
	"strings"
	"unicode/utf8"
)

var (
	ErrNameEmpty        = errors.New("name cannot be empty")
	ErrNameTooLong      = errors.New("name exceeds maximum length of 100 characters")
	ErrNameInvalidRunes = errors.New("name contains invalid characters")
)

// Name represents a validated name value object
type Name struct {
	FirstName string
	LastName  string
}

// maxNameLength is the maximum allowed length for a name
const maxNameLength = 100

// NewName creates a new Name value object with validation
func NewName(firstName, lastName string) (Name, error) {
	firstName = strings.TrimSpace(firstName)
	lastName = strings.TrimSpace(lastName)

	// Allow empty names (user might not provide them yet)
	if firstName == "" && lastName == "" {
		return Name{}, nil
	}

	// Validate first name
	if utf8.RuneCountInString(firstName) > maxNameLength {
		return Name{}, ErrNameTooLong
	}

	// Validate last name
	if utf8.RuneCountInString(lastName) > maxNameLength {
		return Name{}, ErrNameTooLong
	}

	// Basic character validation (allow letters, spaces, hyphens, and some Vietnamese diacritics)
	// This is intentionally permissive as names can contain various Unicode characters
	if !isValidName(firstName) && firstName != "" {
		return Name{}, ErrNameInvalidRunes
	}

	if !isValidName(lastName) && lastName != "" {
		return Name{}, ErrNameInvalidRunes
	}

	return Name{
		FirstName: firstName,
		LastName:  lastName,
	}, nil
}

// MustNewName creates a Name and panics on error
func MustNewName(firstName, lastName string) Name {
	n, err := NewName(firstName, lastName)
	if err != nil {
		panic(err)
	}
	return n
}

// isValidName checks if a name contains only valid characters
// Allows letters (including Unicode letters), spaces, hyphens, apostrophes, and dots
func isValidName(name string) bool {
	if name == "" {
		return true
	}

	for _, r := range name {
		isLetter := (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') ||
			(r >= 0xC0 && r <= 0x24F) || // Latin Extended
			(r >= 0x1E00 && r <= 0x1EFF) || // Latin Extended Additional (Vietnamese)
			(r >= 0x300 && r <= 0x345) // Vietnamese diacritics
		isAllowed := r == ' ' || r == '-' || r == '\'' || r == '.' || r == '(' || r == ')'

		if !isLetter && !isAllowed {
			return false
		}
	}
	return true
}

// FullName returns the full name
func (n Name) FullName() string {
	parts := []string{}
	if n.FirstName != "" {
		parts = append(parts, n.FirstName)
	}
	if n.LastName != "" {
		parts = append(parts, n.LastName)
	}
	return strings.Join(parts, " ")
}

// IsEmpty checks if both first and last name are empty
func (n Name) IsEmpty() bool {
	return n.FirstName == "" && n.LastName == ""
}

// Equal compares two Name values
func (n Name) Equal(other Name) bool {
	return n.FirstName == other.FirstName && n.LastName == other.LastName
}

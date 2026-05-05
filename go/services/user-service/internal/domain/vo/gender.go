package vo

import "errors"

var (
	ErrInvalidGenderValue = errors.New("invalid gender value")
)

// Gender represents the gender enumeration value object
type Gender string

const (
	GenderMale    Gender = "MALE"
	GenderFemale  Gender = "FEMALE"
	GenderOther   Gender = "OTHER"
	GenderUnknown Gender = "UNKNOWN"
)

// ValidGenders contains all valid gender values
var ValidGenders = map[Gender]bool{
	GenderMale:    true,
	GenderFemale:  true,
	GenderOther:   true,
	GenderUnknown: true,
}

// NewGender creates a new Gender value object with validation
func NewGender(value string) (Gender, error) {
	g := Gender(value)
	if !g.IsValid() {
		return GenderUnknown, ErrInvalidGenderValue
	}
	return g, nil
}

// MustNewGender creates a Gender and panics on error
func MustNewGender(value string) Gender {
	g, err := NewGender(value)
	if err != nil {
		panic(err)
	}
	return g
}

// IsValid checks if the gender value is valid
func (g Gender) IsValid() bool {
	return ValidGenders[g]
}

// String returns the gender as a string
func (g Gender) String() string {
	return string(g)
}

// IsMale checks if gender is male
func (g Gender) IsMale() bool {
	return g == GenderMale
}

// IsFemale checks if gender is female
func (g Gender) IsFemale() bool {
	return g == GenderFemale
}

// IsOther checks if gender is other
func (g Gender) IsOther() bool {
	return g == GenderOther
}

// IsUnknown checks if gender is unknown/unspecified
func (g Gender) IsUnknown() bool {
	return g == GenderUnknown
}

// Equal compares two Gender values
func (g Gender) Equal(other Gender) bool {
	return g == other
}

package vo

import (
	"errors"
	"regexp"
)

type Email struct {
	value string
}

var emailRegex = regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)

func NewEmail(value string) (*Email, error) {
	if !emailRegex.MatchString(value) {
		return nil, errors.New("invalid email format")
	}
	return &Email{value: value}, nil
}

func (e *Email) String() string {
	return e.value
}

func (e *Email) Equals(other *Email) bool {
	return e.value == other.value
}

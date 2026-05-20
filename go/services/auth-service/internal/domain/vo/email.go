package vo

import (
	"errors"

	"github.com/ecommerce/auth-service/internal/common/validator"
)

type Email struct {
	value string
}

func NewEmail(value string) (*Email, error) {
	if !validator.IsValidEmail(value) {
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

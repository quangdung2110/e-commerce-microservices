package validator

import (
	"regexp"

	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
)

var phoneRegex = regexp.MustCompile(`^\+?[0-9]{9,15}$`)

// RegisterCustomValidators registers custom validators for Gin
func RegisterCustomValidators() {
	if v, ok := binding.Validator.Engine().(*validator.Validate); ok {
		v.RegisterValidation("e164_phone", validatePhone)
	}
}

func validatePhone(fl validator.FieldLevel) bool {
	phone := fl.Field().String()
	if phone == "" {
		return true // Empty is handled by 'required' tag
	}
	return phoneRegex.MatchString(phone)
}

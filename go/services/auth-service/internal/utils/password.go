package utils

import (
	"fmt"
	"regexp"
	"unicode"
)

const (
	MinPasswordLength = 8
	MaxPasswordLength = 128
)

// PasswordValidationError represents password validation errors
type PasswordValidationError struct {
	Errors []string
}

func (e *PasswordValidationError) Error() string {
	if len(e.Errors) == 1 {
		return e.Errors[0]
	}
	return fmt.Sprintf("password validation failed: %d issues found", len(e.Errors))
}

// ValidatePasswordStrength validates password strength
// Requirements:
//   - Length: 8-128 characters
//   - At least 1 uppercase letter
//   - At least 1 lowercase letter
//   - At least 1 digit
//   - At least 1 special character (!@#$%^&*()_+-=[]{}|;:,.<>?)
func ValidatePasswordStrength(password string) error {
	var errors []string

	// Check length
	if len(password) < MinPasswordLength {
		errors = append(errors, fmt.Sprintf("password must be at least %d characters", MinPasswordLength))
	}
	if len(password) > MaxPasswordLength {
		errors = append(errors, fmt.Sprintf("password must not exceed %d characters", MaxPasswordLength))
	}

	// Check for uppercase letter
	hasUpper := false
	for _, char := range password {
		if unicode.IsUpper(char) {
			hasUpper = true
			break
		}
	}
	if !hasUpper {
		errors = append(errors, "password must contain at least one uppercase letter")
	}

	// Check for lowercase letter
	hasLower := false
	for _, char := range password {
		if unicode.IsLower(char) {
			hasLower = true
			break
		}
	}
	if !hasLower {
		errors = append(errors, "password must contain at least one lowercase letter")
	}

	// Check for digit
	hasDigit := false
	for _, char := range password {
		if unicode.IsDigit(char) {
			hasDigit = true
			break
		}
	}
	if !hasDigit {
		errors = append(errors, "password must contain at least one digit")
	}

	// Check for special character
	specialChars := regexp.MustCompile(`[!@#$%^&*()_+\-=\[\]{}|;:,.<>?]`)
	if !specialChars.MatchString(password) {
		errors = append(errors, "password must contain at least one special character (!@#$%^&*()_+-=[]{}|;:,.<>?)")
	}

	if len(errors) > 0 {
		return &PasswordValidationError{Errors: errors}
	}

	return nil
}

func GenerateTemporaryPassword() (string, error) {
	const (
		lowercaseLetters = "abcdefghijklmnopqrstuvwxyz"
		uppercaseLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
		digits           = "0123456789"
		specialChars     = "!@#$%^&*"
		all              = lowercaseLetters + uppercaseLetters + digits + specialChars
		length           = 12
	)

	password := make([]byte, length)

	// Ensure at least one of each type
	password[0] = lowercaseLetters[RandomInt(len(lowercaseLetters))]
	password[1] = uppercaseLetters[RandomInt(len(uppercaseLetters))]
	password[2] = digits[RandomInt(len(digits))]
	password[3] = specialChars[RandomInt(len(specialChars))]

	// Fill rest with random characters
	for i := 4; i < length; i++ {
		password[i] = all[RandomInt(len(all))]
	}

	// Shuffle
	for i := length - 1; i > 0; i-- {
		j := RandomInt(i + 1)
		password[i], password[j] = password[j], password[i]
	}

	return string(password), nil
}

// ValidatePasswordSimple validates password with basic requirements (for managed accounts)
// Requirements:
//   - Length: 8-128 characters
//   - No whitespace
// func ValidatePasswordSimple(password string) error {
// 	var errors []string

// 	// Check length
// 	if len(password) < MinPasswordLength {
// 		errors = append(errors, fmt.Sprintf("password must be at least %d characters", MinPasswordLength))
// 	}
// 	if len(password) > MaxPasswordLength {
// 		errors = append(errors, fmt.Sprintf("password must not exceed %d characters", MaxPasswordLength))
// 	}

// 	// Check for whitespace
// 	hasWhitespace := false
// 	for _, char := range password {
// 		if unicode.IsSpace(char) {
// 			hasWhitespace = true
// 			break
// 		}
// 	}
// 	if hasWhitespace {
// 		errors = append(errors, "password must not contain whitespace")
// 	}

// 	if len(errors) > 0 {
// 		return &PasswordValidationError{Errors: errors}
// 	}

// 	return nil
// }

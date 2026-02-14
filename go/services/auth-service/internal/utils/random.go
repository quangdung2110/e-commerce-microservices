package utils

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"math/big"
)

const (
	// Character sets for random string generation
	alphanumericChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	numericChars      = "0123456789"
	alphaChars        = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
)

func RandomInt(max int) int {
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(max)))
	return int(n.Int64())
}

// GenerateRandomString generates a cryptographically secure random string
// of the specified length using alphanumeric characters
func GenerateRandomString(length int) (string, error) {
	return generateRandomStringFromCharset(length, alphanumericChars)
}

// GenerateRandomNumeric generates a cryptographically secure random numeric string
// of the specified length (useful for OTP)
func GenerateRandomNumeric(length int) (string, error) {
	return generateRandomStringFromCharset(length, numericChars)
}

// GenerateRandomAlpha generates a cryptographically secure random alphabetic string
// of the specified length
func GenerateRandomAlpha(length int) (string, error) {
	return generateRandomStringFromCharset(length, alphaChars)
}

// GenerateRandomBytes generates cryptographically secure random bytes
func GenerateRandomBytes(length int) ([]byte, error) {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return nil, fmt.Errorf("failed to generate random bytes: %w", err)
	}
	return bytes, nil
}

// GenerateRandomToken generates a cryptographically secure random token
// encoded as base64 URL-safe string
func GenerateRandomToken(byteLength int) (string, error) {
	bytes, err := GenerateRandomBytes(byteLength)
	if err != nil {
		return "", err
	}
	return base64.URLEncoding.EncodeToString(bytes), nil
}

// GenerateOTP generates a cryptographically secure numeric OTP
// of the specified length
func GenerateOTP(length int) (string, error) {
	if length < 4 || length > 10 {
		return "", fmt.Errorf("OTP length must be between 4 and 10")
	}
	return GenerateRandomNumeric(length)
}

// generateRandomStringFromCharset is a helper function that generates
// a random string from a given character set
func generateRandomStringFromCharset(length int, charset string) (string, error) {
	if length <= 0 {
		return "", fmt.Errorf("length must be positive")
	}

	result := make([]byte, length)
	charsetLen := big.NewInt(int64(len(charset)))

	for i := 0; i < length; i++ {
		randomIndex, err := rand.Int(rand.Reader, charsetLen)
		if err != nil {
			return "", fmt.Errorf("failed to generate random number: %w", err)
		}
		result[i] = charset[randomIndex.Int64()]
	}

	return string(result), nil
}

// GenerateDeviceID generates a unique device identifier
func GenerateDeviceID() (string, error) {
	return GenerateRandomToken(32)
}

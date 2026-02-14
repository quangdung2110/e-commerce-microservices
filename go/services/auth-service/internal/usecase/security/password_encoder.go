package security

import (
	"fmt"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"golang.org/x/crypto/bcrypt"
)

// BcryptPasswordEncoder implements PasswordEncoder using bcrypt
type BcryptPasswordEncoder struct {
	cost int
}

// NewBcryptPasswordEncoder creates new bcrypt password encoder
func NewBcryptPasswordEncoder(cost int) common.PasswordEncoder {
	if cost < bcrypt.MinCost || cost > bcrypt.MaxCost {
		cost = bcrypt.DefaultCost
	}
	return &BcryptPasswordEncoder{
		cost: cost,
	}
}

// Hash hashes password with bcrypt
func (e *BcryptPasswordEncoder) Hash(password string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), e.cost)
	if err != nil {
		return "", fmt.Errorf("failed to hash password: %w", err)
	}
	return string(hash), nil
}

// Verify verifies password against hash
func (e *BcryptPasswordEncoder) Verify(password string, hash string) (bool, error) {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	if err == bcrypt.ErrMismatchedHashAndPassword {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("failed to verify password: %w", err)
	}
	return true, nil
}

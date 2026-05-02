package repository

import "errors"

// Repository-level errors
var (
	// Session errors
	ErrSessionNotFound = errors.New("session not found")
	ErrSessionExpired  = errors.New("session expired")
	ErrSessionRevoked  = errors.New("session revoked")

	// Token errors
	ErrTokenNotFound = errors.New("token not found")
	ErrTokenExpired  = errors.New("token expired")
	ErrTokenRevoked  = errors.New("token revoked")

	// Key errors
	ErrKeyNotFound = errors.New("key not found")
	ErrKeyExpired  = errors.New("key expired")

	// Outbox errors
	ErrOutboxEventNotFound = errors.New("outbox event not found")
)

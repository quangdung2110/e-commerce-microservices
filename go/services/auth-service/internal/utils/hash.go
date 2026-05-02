package utils

import (
	"crypto/sha256"
	"encoding/hex"
)

// HashToken generates a SHA-256 hash of a token
// Used for storing refresh token hashes in the database
func HashToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}

// HashTokenBytes generates a SHA-256 hash from byte slice
func HashTokenBytes(token []byte) string {
	hash := sha256.Sum256(token)
	return hex.EncodeToString(hash[:])
}

// VerifyTokenHash verifies that a token matches a hash
func VerifyTokenHash(token, hash string) bool {
	return HashToken(token) == hash
}

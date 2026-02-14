package utils

import (
	"crypto/sha256"
	"encoding/hex"
)

// SHA256Hash generates SHA-256 hash of input string
func SHA256Hash(input string) string {
	hash := sha256.Sum256([]byte(input))
	return hex.EncodeToString(hash[:])
}

// SHA256HashBytes generates SHA-256 hash of input bytes
func SHA256HashBytes(input []byte) string {
	hash := sha256.Sum256(input)
	return hex.EncodeToString(hash[:])
}

// Package secret generates and fingerprints the one-time secrets used by Auth:
// opaque refresh tokens, opaque reset tokens and numeric OTPs. Only fingerprints
// (SHA-256) are ever persisted; the plaintext exists solely in the response or
// in the notification delivery state.
package secret

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"math/big"
	"strings"
)

// NewOpaqueToken returns a URL-safe, unpadded base64 secret with n bytes of
// entropy. 32 bytes produce a 43-character token.
func NewOpaqueToken(n int) (string, error) {
	if n <= 0 {
		return "", fmt.Errorf("secret: token size must be positive")
	}
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("secret: read random bytes: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

// NewNumericOTP returns a uniformly distributed decimal code of the given
// length, zero padded.
func NewNumericOTP(length int) (string, error) {
	if length <= 0 {
		return "", fmt.Errorf("secret: otp length must be positive")
	}
	var sb strings.Builder
	sb.Grow(length)
	for i := 0; i < length; i++ {
		digit, err := rand.Int(rand.Reader, big.NewInt(10))
		if err != nil {
			return "", fmt.Errorf("secret: read random digit: %w", err)
		}
		sb.WriteString(digit.String())
	}
	return sb.String(), nil
}

// Hash returns the raw SHA-256 digest of a secret. Refresh tokens are stored as
// these 32 bytes (auth.refresh_tokens.token_hash).
func Hash(value string) []byte {
	sum := sha256.Sum256([]byte(value))
	return sum[:]
}

// HashHex returns the hex-encoded SHA-256 digest, used for Redis keys and for
// values compared inside Lua scripts.
func HashHex(value string) string {
	return hex.EncodeToString(Hash(value))
}

// Equal compares two digests in constant time.
func Equal(a, b []byte) bool {
	return subtle.ConstantTimeCompare(a, b) == 1
}

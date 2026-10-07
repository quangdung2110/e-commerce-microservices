// Package crypto holds the cryptographic adapters: password hashing, signing-key
// encryption, key generation and JWT signing. Plaintext secrets and private keys
// never leave this package.
package crypto

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.PasswordHasher = (*Argon2Hasher)(nil)

// Argon2Hasher stores passwords as Argon2id PHC strings, as required by
// docs/auth/data.md. The parameters travel inside the hash, so raising them
// later does not invalidate existing credentials.
type Argon2Hasher struct {
	cfg config.Argon2Config
}

// NewArgon2Hasher wires the password hasher.
func NewArgon2Hasher(cfg config.Argon2Config) *Argon2Hasher {
	return &Argon2Hasher{cfg: cfg}
}

// Hash derives a new PHC string with a fresh random salt.
func (h *Argon2Hasher) Hash(password string) (string, error) {
	salt := make([]byte, h.cfg.SaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("crypto: read salt: %w", err)
	}

	digest := argon2.IDKey([]byte(password), salt,
		h.cfg.Iterations, h.cfg.Memory, h.cfg.Parallelism, h.cfg.KeyLength)

	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version,
		h.cfg.Memory, h.cfg.Iterations, h.cfg.Parallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(digest),
	), nil
}

// Verify recomputes the digest with the parameters stored in the hash and
// compares in constant time. A malformed stored hash is an error, a wrong
// password is simply false.
func (h *Argon2Hasher) Verify(encodedHash, password string) (bool, error) {
	if encodedHash == "" {
		return false, nil
	}

	parts := strings.Split(encodedHash, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, fmt.Errorf("crypto: unsupported password hash format")
	}

	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil {
		return false, fmt.Errorf("crypto: unreadable password hash version: %w", err)
	}
	if version != argon2.Version {
		return false, fmt.Errorf("crypto: unsupported argon2 version %d", version)
	}

	var (
		memory      uint32
		iterations  uint32
		parallelism uint8
	)
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &iterations, &parallelism); err != nil {
		return false, fmt.Errorf("crypto: unreadable password hash parameters: %w", err)
	}

	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false, fmt.Errorf("crypto: unreadable password hash salt: %w", err)
	}
	expected, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false, fmt.Errorf("crypto: unreadable password hash digest: %w", err)
	}

	actual := argon2.IDKey([]byte(password), salt, iterations, memory, parallelism, uint32(len(expected)))
	return subtle.ConstantTimeCompare(actual, expected) == 1, nil
}

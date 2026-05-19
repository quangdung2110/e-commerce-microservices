package common

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"

	"golang.org/x/crypto/argon2"
)

type Argon2Config struct {
	Memory      uint32
	Iterations  uint32
	Parallelism uint32
	SaltLength  uint32
	KeyLength   uint32
}

type Argon2Hasher struct {
	config Argon2Config
}

func NewArgon2Hasher(config Argon2Config) *Argon2Hasher {
	if config.Memory == 0 {
		config.Memory = 65536
	}
	if config.Iterations == 0 {
		config.Iterations = 3
	}
	if config.Parallelism == 0 {
		config.Parallelism = 4
	}
	if config.SaltLength == 0 {
		config.SaltLength = 16
	}
	if config.KeyLength == 0 {
		config.KeyLength = 32
	}

	return &Argon2Hasher{
		config: config,
	}
}

func (h *Argon2Hasher) Hash(password string) (string, error) {
	salt := make([]byte, h.config.SaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("failed to generate salt: %w", err)
	}

	hash := argon2.IDKey(
		[]byte(password),
		salt,
		h.config.Iterations,
		h.config.Memory,
		uint8(h.config.Parallelism),
		h.config.KeyLength,
	)

	saltB64 := base64.RawStdEncoding.EncodeToString(salt)
	hashB64 := base64.RawStdEncoding.EncodeToString(hash)

	encoded := fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version,
		h.config.Memory,
		h.config.Iterations,
		h.config.Parallelism,
		saltB64,
		hashB64,
	)

	return encoded, nil
}

func (h *Argon2Hasher) Verify(hash, password string) bool {
	ok, err := argon2idVerify(hash, password)
	if err != nil {
		return false
	}
	return ok
}

func argon2idVerify(encodedHash, password string) (bool, error) {
	var version, memory, iterations, parallelism uint32
	_, err := fmt.Sscanf(encodedHash, "$argon2id$v=%d$m=%d,t=%d,p=%d$", &version, &memory, &iterations, &parallelism)
	if err != nil {
		return false, fmt.Errorf("failed to parse argon2 hash: %w", err)
	}

	parts := splitHash(encodedHash)
	if len(parts) != 2 {
		return false, fmt.Errorf("invalid argon2 hash format")
	}

	salt, err := base64.RawStdEncoding.DecodeString(parts[0])
	if err != nil {
		return false, fmt.Errorf("failed to decode salt: %w", err)
	}

	hash, err := base64.RawStdEncoding.DecodeString(parts[1])
	if err != nil {
		return false, fmt.Errorf("failed to decode hash: %w", err)
	}

	expectedHash := argon2.IDKey(
		[]byte(password),
		salt,
		iterations,
		memory,
		uint8(parallelism),
		uint32(len(hash)),
	)

	if len(hash) != len(expectedHash) {
		return false, nil
	}

	var result byte = 0
	for i := 0; i < len(hash); i++ {
		result |= hash[i] ^ expectedHash[i]
	}

	return result == 0, nil
}

func splitHash(hash string) []string {
	var parts []string
	current := ""
	for i := 0; i < len(hash); i++ {
		if hash[i] == '$' {
			if current != "" {
				parts = append(parts, current)
			}
			current = ""
		} else {
			current += string(hash[i])
		}
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}

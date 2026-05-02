package entity

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"time"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// JwtKey represents a JWT signing key pair
type JwtKey struct {
	Kid                 string           `json:"kid"`
	PublicKey           string           `json:"public_key"`
	PrivateKeyEncrypted string           `json:"private_key_encrypted"`
	Status              types.KeyStatus  `json:"status"`
	Algorithm           string           `json:"algorithm"`
	CreatedAt           time.Time        `json:"created_at"`
	RotatedAt           *time.Time       `json:"rotated_at,omitempty"`
	ExpiresAt           *time.Time       `json:"expires_at,omitempty"`
	Version             int              `json:"version"`
	KeySizeBits         int              `json:"key_size_bits"`
	CreatedBy           string           `json:"created_by"`
}

// NewJwtKey creates a new JWT key
func NewJwtKey(
	kid string,
	publicKey *rsa.PublicKey,
	privateKeyPEM string,
	privateKeyEncrypted string,
	expiresAt *time.Time,
	keySizeBits int,
) *JwtKey {
	return &JwtKey{
		Kid:                 kid,
		PublicKey:           encodeRSAPublicKeyToPEM(publicKey),
		PrivateKeyEncrypted: privateKeyEncrypted,
		Status:              types.KeyStatusActive,
		Algorithm:           "RS256",
		CreatedAt:           time.Now().UTC(),
		ExpiresAt:           expiresAt,
		Version:             1,
		KeySizeBits:         keySizeBits,
		CreatedBy:           "system",
	}
}

// encodeRSAPublicKeyToPEM encodes RSA public key to PEM format
func encodeRSAPublicKeyToPEM(publicKey *rsa.PublicKey) string {
	pubASN1, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		return ""
	}
	block := &pem.Block{
		Type:  "PUBLIC KEY",
		Bytes: pubASN1,
	}
	return string(pem.EncodeToMemory(block))
}

// IsActive returns true if the key is active
func (k *JwtKey) IsActive() bool {
	return k.Status == types.KeyStatusActive
}

// IsPhaseOut returns true if the key is phase out
func (k *JwtKey) IsPhaseOut() bool {
	return k.Status == types.KeyStatusPhaseOut
}

// IsRetired returns true if the key is retired
func (k *JwtKey) IsRetired() bool {
	return k.Status == types.KeyStatusRetired
}

// IsUsableForSigning returns true if the key can be used for signing
func (k *JwtKey) IsUsableForSigning() bool {
	return k.Status == types.KeyStatusActive
}

// IsUsableForVerification returns true if the key can be used for verification
func (k *JwtKey) IsUsableForVerification() bool {
	return k.Status == types.KeyStatusActive || k.Status == types.KeyStatusPhaseOut
}

// IsExpired returns true if the key has expired
func (k *JwtKey) IsExpired() bool {
	if k.ExpiresAt == nil {
		return false
	}
	return time.Now().UTC().After(*k.ExpiresAt)
}

// MarkAsPhaseOut marks the key as phase out
func (k *JwtKey) MarkAsPhaseOut() {
	k.Status = types.KeyStatusPhaseOut
	now := time.Now().UTC()
	k.RotatedAt = &now
	k.Version++
}

// MarkAsRetired marks the key as retired
func (k *JwtKey) MarkAsRetired() {
	k.Status = types.KeyStatusRetired
	now := time.Now().UTC()
	k.RotatedAt = &now
	k.Version++
}

// JWKS represents a JSON Web Key Set
type JWKS struct {
	Keys []JWK `json:"keys"`
}

// JWK represents a JSON Web Key
type JWK struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"` // "RSA"
	Alg string `json:"alg"` // "RS256"
	Use string `json:"use"` // "sig"
	N   string `json:"n"`   // RSA modulus
	E   string `json:"e"`   // RSA exponent
}

// ToJWK converts JwtKey to JWK format
func (k *JwtKey) ToJWK() (*JWK, error) {
	block, _ := pem.Decode([]byte(k.PublicKey))
	if block == nil {
		return nil, ErrInvalidPublicKey
	}

	pubKey, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}

	rsaKey, ok := pubKey.(*rsa.PublicKey)
	if !ok {
		return nil, ErrInvalidPublicKey
	}

	return &JWK{
		Kid: k.Kid,
		Kty: "RSA",
		Alg: k.Algorithm,
		Use: "sig",
		N:   base64.RawURLEncoding.EncodeToString(rsaKey.N.Bytes()),
		E:   base64.RawURLEncoding.EncodeToString(intToBytes(rsaKey.E)),
	}, nil
}

// Custom errors
var (
	ErrInvalidPublicKey = &JwtKeyError{Message: "invalid public key format"}
)

// JwtKeyError represents an error related to JWT keys
type JwtKeyError struct {
	Message string
}

func (e *JwtKeyError) Error() string {
	return e.Message
}

// intToBytes converts an integer to bytes (big-endian)
func intToBytes(n int) []byte {
	result := make([]byte, 0)
	for n > 0 {
		result = append([]byte{byte(n & 0xff)}, result...)
		n >>= 8
	}
	if len(result) == 0 {
		result = []byte{0}
	}
	return result
}

// bytesToInt converts bytes to an integer (big-endian)
func bytesToInt(b []byte) int {
	result := 0
	for _, by := range b {
		result = result<<8 + int(by)
	}
	return result
}

// GetPublicKey parses and returns the RSA public key
func (k *JwtKey) GetPublicKey() (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(k.PublicKey))
	if block == nil {
		return nil, ErrInvalidPublicKey
	}

	pubInterface, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}

	pub, ok := pubInterface.(*rsa.PublicKey)
	if !ok {
		return nil, ErrInvalidPublicKey
	}

	return pub, nil
}

// ParseRSAPublicKeyFromJWK parses RSA components into a public key
func ParseRSAPublicKeyFromJWK(nStr, eStr string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(nStr)
	if err != nil {
		return nil, err
	}

	eBytes, err := base64.RawURLEncoding.DecodeString(eStr)
	if err != nil {
		return nil, err
	}

	n := new(big.Int).SetBytes(nBytes)
	e := bytesToInt(eBytes)

	return &rsa.PublicKey{N: n, E: e}, nil
}

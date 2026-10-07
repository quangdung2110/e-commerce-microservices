package entity

import (
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// JwtKey is one RSA signing key pair. The private half is stored as AES-256-GCM
// ciphertext and is never published or logged (AUTH-INV-003, AUTH-BR-006).
type JwtKey struct {
	KID                  uuid.UUID
	Algorithm            string
	PublicKeyPEM         string
	PrivateKeyCiphertext []byte
	PrivateKeyNonce      []byte
	EncryptionKeyVersion int16
	Status               vo.JwtKeyStatus
	ActivatedAt          *time.Time
	PhaseOutAt           *time.Time
	RetiredAt            *time.Time
	CreatedAt            time.Time
}

// NewJwtKey builds an ACTIVE key from freshly generated, already encrypted
// material.
func NewJwtKey(publicKeyPEM string, ciphertext, nonce []byte, encryptionKeyVersion int16, now time.Time) *JwtKey {
	now = now.UTC()
	activatedAt := now
	return &JwtKey{
		KID:                  uuid.New(),
		Algorithm:            "RS256",
		PublicKeyPEM:         publicKeyPEM,
		PrivateKeyCiphertext: ciphertext,
		PrivateKeyNonce:      nonce,
		EncryptionKeyVersion: encryptionKeyVersion,
		Status:               vo.JwtKeyActive,
		ActivatedAt:          &activatedAt,
		CreatedAt:            now,
	}
}

// IsPublishable reports whether the public half belongs in the JWKS document.
// ACTIVE keys sign new tokens; PHASE_OUT keys still verify tokens in flight.
func (k *JwtKey) IsPublishable() bool {
	return k.Status == vo.JwtKeyActive || k.Status == vo.JwtKeyPhaseOut
}

// Age reports how long the key has been active.
func (k *JwtKey) Age(now time.Time) time.Duration {
	reference := k.CreatedAt
	if k.ActivatedAt != nil {
		reference = *k.ActivatedAt
	}
	return now.UTC().Sub(reference)
}

// RetirementDue reports whether every access token this key could have signed
// has expired, which is the only safe moment to retire it (ADR-003).
func (k *JwtKey) RetirementDue(now time.Time, retirementDelay time.Duration) bool {
	if k.Status != vo.JwtKeyPhaseOut || k.PhaseOutAt == nil {
		return false
	}
	return !now.UTC().Before(k.PhaseOutAt.Add(retirementDelay))
}

package entity

import (
	"time"
)

type KeyStatus string

const (
	KeyStatusActive   KeyStatus = "ACTIVE"
	KeyStatusPhaseOut KeyStatus = "PHASE_OUT"
	KeyStatusRetired  KeyStatus = "RETIRED"
)

type JWTKey struct {
	Kid        string     `json:"kid"`
	PublicKey  string     `json:"public_key"`
	PrivateKey string     `json:"-"`
	Status     KeyStatus  `json:"status"`
	CreatedAt  time.Time  `json:"created_at"`
	RotatedAt  *time.Time `json:"rotated_at,omitempty"`
	ExpiresAt  *time.Time `json:"expires_at,omitempty"`
}

func NewJWTKey(kid, publicKey, privateKey string) *JWTKey {
	return &JWTKey{
		Kid:        kid,
		PublicKey:  publicKey,
		PrivateKey: privateKey,
		Status:     KeyStatusActive,
		CreatedAt:  time.Now(),
	}
}

func (k *JWTKey) IsActive() bool {
	return k.Status == KeyStatusActive
}

func (k *JWTKey) IsPhaseOut() bool {
	return k.Status == KeyStatusPhaseOut
}

func (k *JWTKey) IsRetired() bool {
	return k.Status == KeyStatusRetired
}

func (k *JWTKey) CanSign() bool {
	return k.IsActive()
}

func (k *JWTKey) CanVerify() bool {
	return k.IsActive() || k.IsPhaseOut()
}

func (k *JWTKey) SetPhaseOut() {
	k.Status = KeyStatusPhaseOut
	now := time.Now()
	k.RotatedAt = &now
}

func (k *JWTKey) SetRetired() {
	k.Status = KeyStatusRetired
}

func (k *JWTKey) SetExpiresAt(expiresAt time.Time) {
	k.ExpiresAt = &expiresAt
}

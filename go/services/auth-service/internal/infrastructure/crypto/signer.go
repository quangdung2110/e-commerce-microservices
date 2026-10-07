package crypto

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"math/big"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/ecommerce/auth-service/internal/common/clock"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.Signer = (*Signer)(nil)

// cachedSigningKey is the decrypted ACTIVE key held in memory for a short TTL.
type cachedSigningKey struct {
	kid        string
	privateKey *rsa.PrivateKey
	loadedAt   time.Time
}

type cachedJWKS struct {
	keys     []port.JWK
	loadedAt time.Time
}

// Signer signs access tokens and publishes the public key set.
//
// Decrypted key material is cached for a short TTL to keep a login from
// decrypting the private key on every request. The cache is dropped whenever the
// key lifecycle changes the key set, and it never leaves this process.
type Signer struct {
	keys     port.JwtKeyRepository
	cipher   *KeyCipher
	clock    clock.Clock
	cacheTTL time.Duration

	mu     sync.RWMutex
	active *cachedSigningKey
	jwks   *cachedJWKS
}

// NewSigner wires the JWT signer.
func NewSigner(keys port.JwtKeyRepository, keyCipher *KeyCipher, clk clock.Clock, cacheTTL time.Duration) *Signer {
	return &Signer{keys: keys, cipher: keyCipher, clock: clk, cacheTTL: cacheTTL}
}

// Sign returns the compact JWS and the kid of the signing key. The kid travels
// in the JWT header so a verifier can pick the right key from JWKS.
func (s *Signer) Sign(ctx context.Context, claims map[string]any) (string, string, error) {
	key, err := s.activeKey(ctx)
	if err != nil {
		return "", "", err
	}

	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims(claims))
	token.Header["kid"] = key.kid

	signed, err := token.SignedString(key.privateKey)
	if err != nil {
		return "", "", apperr.ErrInternal.WithCause(err)
	}
	return signed, key.kid, nil
}

// PublicKeys returns the ACTIVE and PHASE_OUT keys as JWKs. A private key is
// never part of this document (AUTH-BR-006).
func (s *Signer) PublicKeys(ctx context.Context) ([]port.JWK, error) {
	s.mu.RLock()
	cached := s.jwks
	s.mu.RUnlock()

	if cached != nil && s.clock.Now().Sub(cached.loadedAt) < s.cacheTTL {
		return cached.keys, nil
	}

	stored, err := s.keys.ListPublishable(ctx)
	if err != nil {
		return nil, err
	}
	if len(stored) == 0 {
		// An empty key set would produce a JWKS document no gateway can use.
		return nil, apperr.ErrDependencyUnavailable.WithDetail("No signing key is published.")
	}

	jwks := make([]port.JWK, 0, len(stored))
	for _, key := range stored {
		jwk, err := toJWK(key)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		jwks = append(jwks, jwk)
	}

	s.mu.Lock()
	s.jwks = &cachedJWKS{keys: jwks, loadedAt: s.clock.Now()}
	s.mu.Unlock()

	return jwks, nil
}

// InvalidateCache drops the cached key material after a rotation or retirement.
func (s *Signer) InvalidateCache() {
	s.mu.Lock()
	s.active = nil
	s.jwks = nil
	s.mu.Unlock()
}

func (s *Signer) activeKey(ctx context.Context) (*cachedSigningKey, error) {
	s.mu.RLock()
	cached := s.active
	s.mu.RUnlock()

	if cached != nil && s.clock.Now().Sub(cached.loadedAt) < s.cacheTTL {
		return cached, nil
	}

	stored, err := s.keys.GetActive(ctx)
	if err != nil {
		// Without a signing key the service must fail closed rather than issue a
		// token nobody can verify.
		return nil, apperr.ErrDependencyUnavailable.WithCause(err)
	}

	der, err := s.cipher.Decrypt(stored.PrivateKeyCiphertext, stored.PrivateKeyNonce, stored.EncryptionKeyVersion)
	if err != nil {
		return nil, apperr.ErrDependencyUnavailable.WithCause(err)
	}
	privateKey, err := parsePrivateKey(der)
	if err != nil {
		return nil, apperr.ErrDependencyUnavailable.WithCause(err)
	}

	key := &cachedSigningKey{
		kid:        stored.KID.String(),
		privateKey: privateKey,
		loadedAt:   s.clock.Now(),
	}

	s.mu.Lock()
	s.active = key
	s.mu.Unlock()

	return key, nil
}

// toJWK renders one stored key as a JSON Web Key.
func toJWK(key *entity.JwtKey) (port.JWK, error) {
	publicKey, err := parsePublicKeyPEM(key.PublicKeyPEM)
	if err != nil {
		return port.JWK{}, err
	}

	return port.JWK{
		Kty: "RSA",
		Kid: key.KID.String(),
		Use: "sig",
		Alg: "RS256",
		N:   base64.RawURLEncoding.EncodeToString(publicKey.N.Bytes()),
		E:   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(publicKey.E)).Bytes()),
	}, nil
}

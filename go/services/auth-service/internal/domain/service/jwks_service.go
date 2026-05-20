package service

import (
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"math/big"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

const JWKSCacheTTL = 5 * time.Minute

var errInvalidPEMBlock = errors.New("failed to decode PEM block")
var errInvalidPublicKey = errors.New("not an RSA public key")

// JWK represents a JSON Web Key
type JWK struct {
	Kty string `json:"kty"` // Key Type (RSA)
	Kid string `json:"kid"` // Key ID
	Use string `json:"use"` // Public Key Use (sig for signature)
	Alg string `json:"alg"` // Algorithm (RS256)
	N   string `json:"n"`   // RSA modulus
	E   string `json:"e"`   // RSA exponent
}

// JWKS represents a JSON Web Key Set
type JWKS struct {
	Keys []JWK `json:"keys"`
}

type JWKSService struct {
	jwtKeyRepo port.JWTKeyRepository
	logger     *zap.Logger
	cache      *jwksCache
}

type jwksCache struct {
	jwks      *JWKS
	expiresAt time.Time
	mu        sync.RWMutex
}

func NewJWKSService(jwtKeyRepo port.JWTKeyRepository, logger *zap.Logger) *JWKSService {
	return &JWKSService{
		jwtKeyRepo: jwtKeyRepo,
		logger:     logger.With(zap.String("service", "JWKSService")),
		cache: &jwksCache{
			jwks:      nil,
			expiresAt: time.Time{},
		},
	}
}

// GetJWKS returns the JSON Web Key Set
func (s *JWKSService) GetJWKS(ctx context.Context) (*JWKS, error) {
	// Check cache first
	s.cache.mu.RLock()
	if s.cache.jwks != nil && time.Now().Before(s.cache.expiresAt) {
		jwks := s.cache.jwks
		s.cache.mu.RUnlock()
		s.logger.Debug("JWKS cache hit", zap.Int("keys_count", len(jwks.Keys)))
		return jwks, nil
	}
	s.cache.mu.RUnlock()

	// Cache miss - fetch from database
	s.logger.Debug("JWKS cache miss, fetching from database")

	keys, err := s.jwtKeyRepo.GetAllForVerification(ctx)
	if err != nil {
		s.logger.Error("Failed to get keys for JWKS", zap.Error(err))
		return nil, err
	}

	// Build JWKS
	jwks := &JWKS{
		Keys: make([]JWK, 0, len(keys)),
	}

	for _, key := range keys {
		jwk, err := keyToJWK(key)
		if err != nil {
			s.logger.Warn("Failed to convert key to JWK",
				zap.Error(err),
				zap.String("kid", key.Kid),
			)
			continue // Skip invalid keys
		}
		jwks.Keys = append(jwks.Keys, *jwk)
	}

	// Update cache
	s.cache.mu.Lock()
	s.cache.jwks = jwks
	s.cache.expiresAt = time.Now().Add(JWKSCacheTTL)
	s.cache.mu.Unlock()

	s.logger.Info("JWKS cache updated", zap.Int("keys_count", len(jwks.Keys)))

	return jwks, nil
}

// InvalidateCache invalidates the JWKS cache
func (s *JWKSService) InvalidateCache() {
	s.cache.mu.Lock()
	s.cache.jwks = nil
	s.cache.expiresAt = time.Time{}
	s.cache.mu.Unlock()
	s.logger.Info("JWKS cache invalidated")
}

// GetCachedJWKS returns the cached JWKS without refresh
func (s *JWKSService) GetCachedJWKS() *JWKS {
	s.cache.mu.RLock()
	defer s.cache.mu.RUnlock()
	return s.cache.jwks
}

// keyToJWK converts a JWTKey entity to JWK format
func keyToJWK(key *entity.JWTKey) (*JWK, error) {
	// Parse public key from PEM
	pubKey, err := parseRSAPublicKey(key.PublicKey)
	if err != nil {
		return nil, err
	}

	return &JWK{
		Kty: "RSA",
		Kid: key.Kid,
		Use: "sig",
		Alg: "RS256",
		N:   base64.RawURLEncoding.EncodeToString(pubKey.N.Bytes()),
		E:   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pubKey.E)).Bytes()),
	}, nil
}

// parseRSAPublicKey parses RSA public key from PEM format
func parseRSAPublicKey(pemStr string) (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, errInvalidPEMBlock
	}

	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}

	rsaPub, ok := pub.(*rsa.PublicKey)
	if !ok {
		return nil, errInvalidPublicKey
	}

	return rsaPub, nil
}

package jwks

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// Service handles JWKS operations
type Service struct {
	keyRepo  repository.JwtKeyRepository
	cfg      config.JWKSConfig
	logger   *zap.Logger
	keyCache *entity.JwtKey // In-memory cache for active key
}

// NewService creates a new JWKS service
func NewService(
	keyRepo repository.JwtKeyRepository,
	cfg config.JWKSConfig,
	logger *zap.Logger,
) *Service {
	return &Service{
		keyRepo: keyRepo,
		cfg:     cfg,
		logger:  logger,
	}
}

// GetActivePrivateKey returns the active private key for signing tokens
func (s *Service) GetActivePrivateKey(ctx context.Context) (*rsa.PrivateKey, string, error) {
	// Check in-memory cache first
	if s.keyCache != nil && s.keyCache.IsUsableForSigning() && !s.keyCache.IsExpired() {
		privateKey, err := s.parsePrivateKey(s.keyCache.PrivateKeyEncrypted)
		if err == nil {
			return privateKey, s.keyCache.Kid, nil
		}
		s.logger.Warn("Failed to parse cached private key, fetching from DB", zap.Error(err))
	}

	// Fetch from database
	key, err := s.keyRepo.GetActive(ctx)
	if err != nil {
		if errors.Is(err, repository.ErrKeyNotFound) {
			s.logger.Info("No active key found, generating new key pair")
			key, err = s.generateAndStoreKey(ctx)
			if err != nil {
				return nil, "", fmt.Errorf("failed to generate key: %w", err)
			}
		} else {
			return nil, "", fmt.Errorf("failed to get active key: %w", err)
		}
	}

	if !key.IsUsableForSigning() {
		return nil, "", serr.ErrKeyExpired
	}

	if key.IsExpired() {
		s.logger.Warn("Active key has expired, rotating")
		key, err = s.rotateKey(ctx)
		if err != nil {
			return nil, "", fmt.Errorf("failed to rotate key: %w", err)
		}
	}

	// Update cache
	s.keyCache = key

	privateKey, err := s.parsePrivateKey(key.PrivateKeyEncrypted)
	if err != nil {
		return nil, "", fmt.Errorf("failed to parse private key: %w", err)
	}

	return privateKey, key.Kid, nil
}

// GetPublicKeys returns all active public keys for token verification (includes PHASE_OUT keys)
func (s *Service) GetPublicKeys(ctx context.Context) ([]*entity.JWK, error) {
	keys, err := s.keyRepo.GetAllActive(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get public keys: %w", err)
	}

	jwks := make([]*entity.JWK, 0, len(keys))
	for _, key := range keys {
		if key.IsUsableForVerification() {
			jwk, err := key.ToJWK()
			if err != nil {
				s.logger.Warn("Failed to convert key to JWK", zap.String("kid", key.Kid), zap.Error(err))
				continue
			}
			jwks = append(jwks, jwk)
		}
	}

	return jwks, nil
}

// GetJWKS returns the JSON Web Key Set
func (s *Service) GetJWKS(ctx context.Context) (*entity.JWKS, error) {
	keys, err := s.GetPublicKeys(ctx)
	if err != nil {
		return nil, err
	}

	return &entity.JWKS{
		Keys: keys,
	}, nil
}

// generateAndStoreKey generates a new RSA key pair and stores it
func (s *Service) generateAndStoreKey(ctx context.Context) (*entity.JwtKey, error) {
	kid := uuid.New().String()

	privateKey, err := rsa.GenerateKey(rand.Reader, s.cfg.KeySizeBits)
	if err != nil {
		return nil, fmt.Errorf("failed to generate RSA key: %w", err)
	}

	privateKeyPEM, err := encodePrivateKeyToPEM(privateKey)
	if err != nil {
		return nil, fmt.Errorf("failed to encode private key: %w", err)
	}

	// For now, we don't encrypt at rest (encryption can be added later)
	privateKeyEncrypted := privateKeyPEM

	expiresAt := time.Now().Add(domain.KeyRotationInterval)
	if s.cfg.RotationInterval != "" {
		duration, err := time.ParseDuration(s.cfg.RotationInterval)
		if err == nil {
			expiresAt = time.Now().Add(duration)
		}
	}

	key := entity.NewJwtKey(
		kid,
		&privateKey.PublicKey,
		privateKeyPEM,
		privateKeyEncrypted,
		&expiresAt,
		s.cfg.KeySizeBits,
	)

	if err := s.keyRepo.Create(ctx, key); err != nil {
		return nil, fmt.Errorf("failed to store key: %w", err)
	}

	s.keyCache = key

	s.logger.Info("Generated new JWT key",
		zap.String("kid", kid),
		zap.Int("key_size_bits", s.cfg.KeySizeBits),
		zap.Time("expires_at", expiresAt))

	return key, nil
}

// rotateKey rotates the active key
func (s *Service) rotateKey(ctx context.Context) (*entity.JwtKey, error) {
	// Mark current active key as phase out
	currentKey, err := s.keyRepo.GetActive(ctx)
	if err == nil && currentKey != nil {
		if err := s.keyRepo.UpdateStatus(ctx, currentKey.Kid, types.KeyStatusPhaseOut); err != nil {
			s.logger.Warn("Failed to mark current key as phase out", zap.Error(err))
		} else {
			s.logger.Info("Marked current key as phase out", zap.String("kid", currentKey.Kid))
		}
	}

	// Generate new key
	return s.generateAndStoreKey(ctx)
}

// parsePrivateKey parses a PEM-encoded private key
func (s *Service) parsePrivateKey(pemData string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemData))
	if block == nil {
		return nil, errors.New("failed to decode PEM block")
	}

	switch block.Type {
	case "RSA PRIVATE KEY":
		return x509.ParsePKCS1PrivateKey(block.Bytes)
	case "PRIVATE KEY":
		key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		rsaKey, ok := key.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("not an RSA private key")
		}
		return rsaKey, nil
	default:
		return nil, fmt.Errorf("unknown PEM type: %s", block.Type)
	}
}

// encodePrivateKeyToPEM encodes an RSA private key to PEM format
func encodePrivateKeyToPEM(privateKey *rsa.PrivateKey) (string, error) {
	pkcs8Bytes, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		// Fall back to PKCS1
		pkcs1Bytes := x509.MarshalPKCS1PrivateKey(privateKey)
		block := &pem.Block{
			Type:  "RSA PRIVATE KEY",
			Bytes: pkcs1Bytes,
		}
		return string(pem.EncodeToMemory(block)), nil
	}

	block := &pem.Block{
		Type:  "PRIVATE KEY",
		Bytes: pkcs8Bytes,
	}
	return string(pem.EncodeToMemory(block)), nil
}

// GetKeyByKid retrieves a key by its ID
func (s *Service) GetKeyByKid(ctx context.Context, kid string) (*entity.JwtKey, error) {
	return s.keyRepo.GetByKid(ctx, kid)
}

// InvalidateCache invalidates the in-memory key cache
func (s *Service) InvalidateCache() {
	s.keyCache = nil
	s.logger.Info("JWKS cache invalidated")
}

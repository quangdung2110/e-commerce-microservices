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
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/infrastructure/redis_pubsub"
)

// Service handles JWKS operations
type Service struct {
	keyRepo  repository.JwtKeyRepository
	cfg      config.JWKSConfig
	logger   *zap.Logger
	cache    *Cache
	pubsub   *redis_pubsub.Publisher
	keyCache *entity.JwtKey // In-memory cache for active signing key
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

// InitializeWithCacheAndPubsub initializes the service with cache and pub/sub
func (s *Service) InitializeWithCacheAndPubsub(
	cache *Cache,
	pubsub *redis_pubsub.Publisher,
) error {
	s.cache = cache
	s.pubsub = pubsub
	return nil
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
	// Try cache first
	if s.cache != nil {
		jwks, err := s.cache.Get()
		if err == nil && jwks != nil {
			return jwks.Keys, nil
		}
	}

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

	// Update cache
	if s.cache != nil && len(jwks) > 0 {
		cached := &entity.JWKS{Keys: jwks}
		s.cache.Set(cached)
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
	kid := s.generateKid()

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

	// Invalidate cache and notify
	s.invalidateCacheAndNotify(kid, "KEY_GENERATED")

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

// RotateKey performs a key rotation
// This method is safe to call and handles all cleanup
func (s *Service) RotateKey(ctx context.Context) error {
	s.logger.Info("Starting key rotation")

	_, err := s.rotateKey(ctx)
	if err != nil {
		return fmt.Errorf("failed to rotate key: %w", err)
	}

	s.logger.Info("Key rotation completed successfully")
	return nil
}

// generateKid generates a unique key ID
func (s *Service) generateKid() string {
	return fmt.Sprintf("key-%s", uuid.New().String())
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

	// Invalidate JWKS cache
	if s.cache != nil {
		s.cache.Invalidate()
	}

	s.logger.Info("JWKS cache invalidated")
}

// invalidateCacheAndNotify invalidates cache and notifies via Pub/Sub
func (s *Service) invalidateCacheAndNotify(kid string, reason string) {
	// Invalidate cache
	if s.cache != nil {
		s.cache.Invalidate()
	}

	// Publish notification
	if s.pubsub != nil {
		ctx := context.Background()
		if err := s.pubsub.PublishJwksUpdate(ctx, kid, reason); err != nil {
			s.logger.Warn("Failed to publish JWKS update", zap.Error(err))
		}
	}
}

// IsKeyRotationNeeded checks if key rotation is needed based on expiry
func (s *Service) IsKeyRotationNeeded(ctx context.Context) (bool, error) {
	key, err := s.keyRepo.GetActive(ctx)
	if err != nil {
		if errors.Is(err, repository.ErrKeyNotFound) {
			return true, nil // No key exists, need to generate
		}
		return false, err
	}

	if key.IsExpired() {
		return true, nil
	}

	// Check if key is approaching expiry (within 7 days)
	if key.ExpiresAt != nil {
		daysUntilExpiry := time.Until(*key.ExpiresAt).Hours() / 24
		if daysUntilExpiry <= 7 {
			return true, nil
		}
	}

	return false, nil
}

// GetKeyInfo returns information about the current active key
func (s *Service) GetKeyInfo(ctx context.Context) (*KeyInfo, error) {
	key, err := s.keyRepo.GetActive(ctx)
	if err != nil {
		if errors.Is(err, repository.ErrKeyNotFound) {
			return nil, nil
		}
		return nil, err
	}

	info := &KeyInfo{
		Kid:        key.Kid,
		Status:     string(key.Status),
		Algorithm:  key.Algorithm,
		KeySizeBits: key.KeySizeBits,
		CreatedAt:  key.CreatedAt,
		ExpiresAt:  key.ExpiresAt,
	}

	if key.RotatedAt != nil {
		info.RotatedAt = key.RotatedAt
	}

	if key.ExpiresAt != nil {
		info.DaysUntilExpiry = int(time.Until(*key.ExpiresAt).Hours() / 24)
	}

	return info, nil
}

// KeyInfo represents information about a key
type KeyInfo struct {
	Kid            string     `json:"kid"`
	Status         string     `json:"status"`
	Algorithm      string     `json:"algorithm"`
	KeySizeBits    int        `json:"key_size_bits"`
	CreatedAt      time.Time  `json:"created_at"`
	RotatedAt      *time.Time `json:"rotated_at,omitempty"`
	ExpiresAt      *time.Time `json:"expires_at,omitempty"`
	DaysUntilExpiry int       `json:"days_until_expiry,omitempty"`
}

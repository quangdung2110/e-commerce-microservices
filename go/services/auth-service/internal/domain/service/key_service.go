package service

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

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// Errors
var (
	ErrNoActiveKey   = errors.New("no active key found")
	ErrKeyGeneration = errors.New("failed to generate key pair")
)

type KeyService struct {
	jwtKeyRepo port.JWTKeyRepository
	logger     *zap.Logger
}

func NewKeyService(jwtKeyRepo port.JWTKeyRepository, logger *zap.Logger) *KeyService {
	return &KeyService{
		jwtKeyRepo: jwtKeyRepo,
		logger:     logger.With(zap.String("service", "KeyService")),
	}
}

// GenerateKeyPair generates a new RSA key pair
func (s *KeyService) GenerateKeyPair(ctx context.Context) (*entity.JWTKey, error) {
	privateKey, err := rsa.GenerateKey(rand.Reader, constants.RSAKeySizeDefault)
	if err != nil {
		s.logger.Error("Failed to generate RSA key pair", zap.Error(err))
		return nil, fmt.Errorf("%w: %v", ErrKeyGeneration, err)
	}

	privateKeyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
	})

	publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		s.logger.Error("Failed to marshal public key", zap.Error(err))
		return nil, fmt.Errorf("failed to marshal public key: %w", err)
	}

	publicKeyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PUBLIC KEY",
		Bytes: publicKeyBytes,
	})

	kid := uuid.New().String()

	jwtKey := &entity.JWTKey{
		Kid:        kid,
		PublicKey:  string(publicKeyPEM),
		PrivateKey: string(privateKeyPEM),
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now().UTC(),
	}

	s.logger.Info("Generated new RSA key pair",
		zap.String("kid", kid),
		zap.Int("key_size", constants.RSAKeySizeDefault),
	)

	return jwtKey, nil
}

// CreateNewActiveKey creates a new active key and phases out the old one
func (s *KeyService) CreateNewActiveKey(ctx context.Context) (*entity.JWTKey, error) {
	currentKey, err := s.jwtKeyRepo.GetActive(ctx)
	if err != nil && !errors.Is(err, port.ErrNotFound) {
		s.logger.Error("Failed to get current active key", zap.Error(err))
		return nil, fmt.Errorf("failed to get current key: %w", err)
	}

	newKey, err := s.GenerateKeyPair(ctx)
	if err != nil {
		s.logger.Error("Failed to generate new key pair", zap.Error(err))
		return nil, fmt.Errorf("failed to generate key pair: %w", err)
	}

	if err := s.jwtKeyRepo.Create(ctx, newKey); err != nil {
		s.logger.Error("Failed to save new key", zap.Error(err), zap.String("kid", newKey.Kid))
		return nil, fmt.Errorf("failed to save new key: %w", err)
	}

	s.logger.Info("New key saved to database", zap.String("kid", newKey.Kid))

	if currentKey != nil {
		if err := s.jwtKeyRepo.UpdateStatus(ctx, currentKey.Kid, string(entity.KeyStatusPhaseOut)); err != nil {
			s.logger.Warn("Failed to phase out old key",
				zap.Error(err),
				zap.String("old_kid", currentKey.Kid),
			)
		} else {
			s.logger.Info("Old key phased out",
				zap.String("old_kid", currentKey.Kid),
				zap.String("new_kid", newKey.Kid),
			)
		}
	}

	return newKey, nil
}

// GetActiveKey retrieves the current active key
func (s *KeyService) GetActiveKey(ctx context.Context) (*entity.JWTKey, error) {
	key, err := s.jwtKeyRepo.GetActive(ctx)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			s.logger.Warn("No active key found in database")
			return nil, ErrNoActiveKey
		}
		s.logger.Error("Failed to get active key", zap.Error(err))
		return nil, fmt.Errorf("failed to get active key: %w", err)
	}
	return key, nil
}

// GetAllVerificationKeys retrieves all keys that can verify tokens (ACTIVE and PHASE_OUT)
func (s *KeyService) GetAllVerificationKeys(ctx context.Context) ([]*entity.JWTKey, error) {
	keys, err := s.jwtKeyRepo.GetAllForVerification(ctx)
	if err != nil {
		s.logger.Error("Failed to get verification keys", zap.Error(err))
		return nil, fmt.Errorf("failed to get verification keys: %w", err)
	}

	s.logger.Debug("Retrieved verification keys", zap.Int("count", len(keys)))

	return keys, nil
}

// GetSigningKey retrieves the key for signing tokens
func (s *KeyService) GetSigningKey(ctx context.Context) (*entity.JWTKey, error) {
	key, err := s.GetActiveKey(ctx)
	if err != nil {
		return nil, err
	}

	if !key.CanSign() {
		return nil, fmt.Errorf("active key cannot sign: %s", key.Kid)
	}

	return key, nil
}

// GetKeyByKid retrieves a key by its ID
func (s *KeyService) GetKeyByKid(ctx context.Context, kid string) (*entity.JWTKey, error) {
	key, err := s.jwtKeyRepo.GetByKid(ctx, kid)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return nil, fmt.Errorf("key not found: %s", kid)
		}
		s.logger.Error("Failed to get key by kid", zap.Error(err), zap.String("kid", kid))
		return nil, fmt.Errorf("failed to get key: %w", err)
	}
	return key, nil
}

// RetireOldKeys retires keys that are past their grace period
func (s *KeyService) RetireOldKeys(ctx context.Context) error {
	retirementDelay := constants.KeyRetirementDelayDefault
	threshold := time.Now().Add(-retirementDelay)

	keys, err := s.jwtKeyRepo.GetPhaseOutKeys(ctx, threshold)
	if err != nil {
		s.logger.Error("Failed to get phase out keys", zap.Error(err))
		return fmt.Errorf("failed to get phase out keys: %w", err)
	}

	if len(keys) == 0 {
		s.logger.Debug("No keys ready for retirement")
		return nil
	}

	s.logger.Info("Found keys ready for retirement", zap.Int("count", len(keys)))

	for _, key := range keys {
		if err := s.jwtKeyRepo.UpdateStatus(ctx, key.Kid, string(entity.KeyStatusRetired)); err != nil {
			s.logger.Error("Failed to retire key",
				zap.Error(err),
				zap.String("kid", key.Kid),
			)
			continue
		}
		s.logger.Info("Key retired", zap.String("kid", key.Kid))
	}

	return nil
}

// EnsureActiveKey ensures there is always an active key
func (s *KeyService) EnsureActiveKey(ctx context.Context) error {
	key, err := s.jwtKeyRepo.GetActive(ctx)
	if err != nil && !errors.Is(err, port.ErrNotFound) {
		return fmt.Errorf("failed to check for active key: %w", err)
	}

	if key == nil {
		s.logger.Info("No active key found, generating initial key")
		_, err := s.CreateNewActiveKey(ctx)
		if err != nil {
			return fmt.Errorf("failed to create initial key: %w", err)
		}
		s.logger.Info("Initial key created successfully")
	}

	return nil
}

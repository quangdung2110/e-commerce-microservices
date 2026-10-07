package service

import (
	"context"
	"errors"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// KeyPolicy is the configured signing-key lifecycle.
type KeyPolicy struct {
	// RotationInterval is the age at which the ACTIVE key is replaced.
	RotationInterval time.Duration
	// RetirementDelay is the overlap a PHASE_OUT key keeps in JWKS. It must be at
	// least the maximum access-token lifetime (ADR-003).
	RetirementDelay time.Duration
}

// KeyLifecycleService implements WF-AUTH-008: keep exactly one ACTIVE signing
// key, phase the previous one out and retire it only once no token it signed can
// still be presented.
type KeyLifecycleService struct {
	keys      port.JwtKeyRepository
	generator port.KeyGenerator
	signer    port.Signer
	notifier  port.JwksNotifier
	clock     clock.Clock
	policy    KeyPolicy
	logger    *zap.Logger
}

// NewKeyLifecycleService wires the key rotation and retirement use cases.
func NewKeyLifecycleService(
	keys port.JwtKeyRepository,
	generator port.KeyGenerator,
	signer port.Signer,
	notifier port.JwksNotifier,
	clk clock.Clock,
	policy KeyPolicy,
	logger *zap.Logger,
) *KeyLifecycleService {
	return &KeyLifecycleService{
		keys:      keys,
		generator: generator,
		signer:    signer,
		notifier:  notifier,
		clock:     clk,
		policy:    policy,
		logger:    logger,
	}
}

// EnsureActiveKey creates the first signing key when the service starts against
// an empty key table. It never replaces an existing ACTIVE key.
func (s *KeyLifecycleService) EnsureActiveKey(ctx context.Context) error {
	_, err := s.keys.GetActive(ctx)
	if err == nil {
		return nil
	}
	if !errors.Is(err, apperr.ErrSigningKeyMissing) {
		return err
	}

	key, err := s.newKey()
	if err != nil {
		return err
	}
	if err := s.keys.Create(ctx, key); err != nil {
		return err
	}
	s.afterKeySetChanged(ctx, "created the first signing key", key.KID.String())
	return nil
}

// RotateIfDue replaces the ACTIVE key once it reaches the rotation age. The old
// key moves to PHASE_OUT in the same transaction, so there is never a moment
// without exactly one ACTIVE key.
func (s *KeyLifecycleService) RotateIfDue(ctx context.Context) (bool, error) {
	active, err := s.keys.GetActive(ctx)
	if err != nil {
		if errors.Is(err, apperr.ErrSigningKeyMissing) {
			return true, s.EnsureActiveKey(ctx)
		}
		return false, err
	}
	if active.Age(s.clock.Now()) < s.policy.RotationInterval {
		return false, nil
	}

	key, err := s.newKey()
	if err != nil {
		return false, err
	}
	if err := s.keys.Rotate(ctx, key, s.clock.Now()); err != nil {
		return false, err
	}
	s.afterKeySetChanged(ctx, "rotated the signing key", key.KID.String())
	return true, nil
}

// RetireDue retires PHASE_OUT keys whose overlap window elapsed and reports how
// many were retired.
func (s *KeyLifecycleService) RetireDue(ctx context.Context) (int, error) {
	now := s.clock.Now()
	candidates, err := s.keys.ListRetirable(ctx, now.Add(-s.policy.RetirementDelay))
	if err != nil {
		return 0, err
	}

	retired := 0
	for _, key := range candidates {
		if !key.RetirementDue(now, s.policy.RetirementDelay) {
			// Defensive: never retire a key that could still verify a live token.
			continue
		}
		if err := s.keys.Retire(ctx, key.KID, now); err != nil {
			s.logger.Error("could not retire a signing key",
				zap.String("kid", key.KID.String()),
				zap.Error(err),
			)
			continue
		}
		retired++
	}
	if retired > 0 {
		s.afterKeySetChanged(ctx, "retired phase-out signing keys", "")
	}
	return retired, nil
}

func (s *KeyLifecycleService) newKey() (*entity.JwtKey, error) {
	material, err := s.generator.Generate()
	if err != nil {
		return nil, err
	}
	return entity.NewJwtKey(
		material.PublicKeyPEM,
		material.PrivateKeyCiphertext,
		material.PrivateKeyNonce,
		material.EncryptionKeyVersion,
		s.clock.Now(),
	), nil
}

// afterKeySetChanged drops the local signer cache and tells the gateway caches
// to refresh. The Pub/Sub signal is an optimization: a gateway must still
// refresh JWKS when it meets an unknown kid.
func (s *KeyLifecycleService) afterKeySetChanged(ctx context.Context, message, kid string) {
	s.signer.InvalidateCache()
	if err := s.notifier.NotifyKeysChanged(ctx); err != nil {
		s.logger.Warn("could not publish the JWKS invalidation signal", zap.Error(err))
	}
	fields := []zap.Field{}
	if kid != "" {
		fields = append(fields, zap.String("kid", kid))
	}
	s.logger.Info(message, fields...)
}

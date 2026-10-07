//go:build integration

package integration_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
)

func newKey(now time.Time) *entity.JwtKey {
	// The nonce column is constrained to exactly 12 bytes.
	return entity.NewJwtKey("public-key-pem", []byte("ciphertext"), []byte("123456789012"), 1, now)
}

// Token issuance must fail closed when the key table is empty, and the empty
// state must be distinguishable from a database outage.
func TestJwtKeyRepositoryReportsAMissingSigningKey(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	keys := postgres.NewJwtKeyRepository(testPool)

	_, err := keys.GetActive(ctx)
	require.ErrorIs(t, err, apperr.ErrSigningKeyMissing)

	publishable, err := keys.ListPublishable(ctx)
	require.NoError(t, err)
	require.Empty(t, publishable)
}

func TestJwtKeyRepositoryRoundTrip(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	keys := postgres.NewJwtKeyRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	key := newKey(now)
	require.NoError(t, keys.Create(ctx, key))

	active, err := keys.GetActive(ctx)
	require.NoError(t, err)
	require.Equal(t, key.KID, active.KID)
	require.Equal(t, "RS256", active.Algorithm)
	require.Equal(t, vo.JwtKeyActive, active.Status)
	require.Equal(t, []byte("ciphertext"), active.PrivateKeyCiphertext)
	require.Len(t, active.PrivateKeyNonce, 12)
	require.Equal(t, int16(1), active.EncryptionKeyVersion)
	require.NotNil(t, active.ActivatedAt)
}

// WF-AUTH-008: rotation phases the old key out and installs the new one in one
// transaction, so the "one active key" index is never violated.
func TestJwtKeyRotationKeepsExactlyOneActiveKey(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	keys := postgres.NewJwtKeyRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	first := newKey(now)
	require.NoError(t, keys.Create(ctx, first))

	rotatedAt := now.Add(30 * 24 * time.Hour)
	second := newKey(rotatedAt)
	require.NoError(t, keys.Rotate(ctx, second, rotatedAt))

	active, err := keys.GetActive(ctx)
	require.NoError(t, err)
	require.Equal(t, second.KID, active.KID)

	// Both keys stay in JWKS during the overlap, so tokens signed by the old key
	// still verify.
	publishable, err := keys.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 2)

	var activeCount, phaseOutCount int
	for _, key := range publishable {
		switch key.Status {
		case vo.JwtKeyActive:
			activeCount++
		case vo.JwtKeyPhaseOut:
			phaseOutCount++
			require.NotNil(t, key.PhaseOutAt)
		}
	}
	require.Equal(t, 1, activeCount)
	require.Equal(t, 1, phaseOutCount)
}

// A second ACTIVE key inserted outside the rotation path must be rejected by the
// unique index rather than silently accepted.
func TestJwtKeyRepositoryRefusesTwoActiveKeys(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	keys := postgres.NewJwtKeyRepository(testPool)
	now := time.Now().UTC()

	require.NoError(t, keys.Create(ctx, newKey(now)))
	require.Error(t, keys.Create(ctx, newKey(now)))
}

// ADR-003: a phase-out key is retired only after the overlap window.
func TestJwtKeyRetirement(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	keys := postgres.NewJwtKeyRepository(testPool)
	now := time.Now().UTC()

	require.NoError(t, keys.Create(ctx, newKey(now)))
	rotatedAt := now.Add(time.Hour)
	require.NoError(t, keys.Rotate(ctx, newKey(rotatedAt), rotatedAt))

	// Nothing has been phased out long enough yet.
	retirable, err := keys.ListRetirable(ctx, rotatedAt.Add(-time.Minute))
	require.NoError(t, err)
	require.Empty(t, retirable)

	retirable, err = keys.ListRetirable(ctx, rotatedAt.Add(25*time.Hour))
	require.NoError(t, err)
	require.Len(t, retirable, 1)

	require.NoError(t, keys.Retire(ctx, retirable[0].KID, rotatedAt.Add(25*time.Hour)))

	publishable, err := keys.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 1, "a retired key leaves JWKS")
	require.Equal(t, vo.JwtKeyActive, publishable[0].Status)

	// Retiring a key another replica already retired is not an error.
	require.NoError(t, keys.Retire(ctx, retirable[0].KID, rotatedAt.Add(26*time.Hour)))
	// Retiring an unknown key is a no-op as well.
	require.NoError(t, keys.Retire(ctx, uuid.New(), rotatedAt.Add(26*time.Hour)))
}

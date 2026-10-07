package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// A service that starts against an empty key table creates its first key, and a
// restart does not create a second one.
func TestEnsureActiveKeyCreatesExactlyOneKey(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.keyLifecycle.EnsureActiveKey(ctx))
	require.Equal(t, 1, h.generator.generated)

	require.NoError(t, h.keyLifecycle.EnsureActiveKey(ctx))
	require.Equal(t, 1, h.generator.generated, "an existing active key is never replaced at startup")

	active, err := h.keys.GetActive(ctx)
	require.NoError(t, err)
	require.Equal(t, vo.JwtKeyActive, active.Status)
}

// WF-AUTH-008: rotation only happens once the key reaches its configured age.
func TestRotateIfDueWaitsForTheRotationInterval(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()
	require.NoError(t, h.keyLifecycle.EnsureActiveKey(ctx))

	rotated, err := h.keyLifecycle.RotateIfDue(ctx)
	require.NoError(t, err)
	require.False(t, rotated)

	h.clock.Advance(31 * 24 * time.Hour)
	rotated, err = h.keyLifecycle.RotateIfDue(ctx)
	require.NoError(t, err)
	require.True(t, rotated)

	// Exactly one ACTIVE key, and the previous one is still publishable so the
	// tokens it signed keep verifying.
	publishable, err := h.keys.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 2)

	active := 0
	phaseOut := 0
	for _, key := range publishable {
		switch key.Status {
		case vo.JwtKeyActive:
			active++
		case vo.JwtKeyPhaseOut:
			phaseOut++
		}
	}
	require.Equal(t, 1, active)
	require.Equal(t, 1, phaseOut)

	// The gateway caches are told to refresh and the local cache is dropped.
	require.Positive(t, h.jwks.notified)
	require.Positive(t, h.signer.invalidated)
}

// ADR-003: a phase-out key is retired only after the overlap window, so no live
// access token loses its verification key.
func TestRetireDueKeepsTheOverlapWindow(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()
	require.NoError(t, h.keyLifecycle.EnsureActiveKey(ctx))

	h.clock.Advance(31 * 24 * time.Hour)
	_, err := h.keyLifecycle.RotateIfDue(ctx)
	require.NoError(t, err)

	// The retirement delay of the test policy is 24 hours.
	retired, err := h.keyLifecycle.RetireDue(ctx)
	require.NoError(t, err)
	require.Zero(t, retired)

	h.clock.Advance(25 * time.Hour)
	retired, err = h.keyLifecycle.RetireDue(ctx)
	require.NoError(t, err)
	require.Equal(t, 1, retired)

	publishable, err := h.keys.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 1, "only the active key remains in JWKS")
	require.Equal(t, vo.JwtKeyActive, publishable[0].Status)
}

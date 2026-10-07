package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// WF-AUTH-003: a refresh returns a new pair inside the same session family.
func TestRefreshRotatesInsideTheSameSession(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	login := h.login(t)

	rotated, err := h.sessions.Refresh(context.Background(), service.RefreshCommand{
		RefreshToken: login.RefreshToken,
		IPAddress:    "203.0.113.10",
	})
	require.NoError(t, err)

	require.NotEqual(t, login.RefreshToken, rotated.RefreshToken)
	require.Equal(t, login.SessionID, rotated.SessionID, "rotation keeps the session id stable")
	require.NotEmpty(t, rotated.Access.Token)
}

// ADR-002: no grace period. The consumed token fails on replay and the token it
// produced keeps working.
func TestRefreshRejectsAReplayedToken(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	rotated, err := h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.NoError(t, err)

	_, replayErr := h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, replayErr, apperr.ErrRefreshTokenInvalid)

	// The legitimate successor is untouched by the replay.
	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: rotated.RefreshToken})
	require.NoError(t, err)
}

func TestRefreshRejectsAnUnknownToken(t *testing.T) {
	h := newHarness(t)

	_, err := h.sessions.Refresh(context.Background(), service.RefreshCommand{RefreshToken: "not-a-token"})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
}

func TestRefreshRejectsAnExpiredToken(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	login := h.login(t)

	// The sliding TTL of the test policy is seven days.
	h.clock.Advance(8 * 24 * time.Hour)

	_, err := h.sessions.Refresh(context.Background(), service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
}

// The sliding expiry may never pass the absolute expiry of the session.
func TestRefreshDoesNotExtendBeyondTheAbsoluteExpiry(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	login := h.login(t)
	absoluteExpiry := testNow.Add(30 * 24 * time.Hour)

	// Four rotations six days apart reach the cap: the last one would extend to
	// day 31 but is clamped to the absolute expiry on day 30.
	current := login.RefreshToken
	for i := 0; i < 4; i++ {
		h.clock.Advance(6 * 24 * time.Hour)
		rotated, err := h.sessions.Refresh(context.Background(), service.RefreshCommand{RefreshToken: current})
		require.NoError(t, err)
		require.False(t, rotated.RefreshExpiresAt.After(absoluteExpiry))
		current = rotated.RefreshToken
	}
}

// AUTH-BR-001: a refresh cannot revive a deleted or otherwise inactive account.
func TestRefreshRejectsAnInactiveAccount(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	require.NoError(t, account.MarkDeleted(vo.DeletionGDPRRequest, h.clock.Now()))
	require.NoError(t, h.accounts.Update(ctx, account))

	_, err := h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
}

// WF-AUTH-004: revoking one session ends that session only.
func TestRevokeSessionEndsOnlyThatSession(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	first := h.login(t)
	second := h.login(t)
	ctx := context.Background()

	result, err := h.sessions.RevokeSession(ctx, service.RevokeSessionCommand{
		AccountID: account.ID,
		SessionID: first.SessionID,
		TokenJti:  first.Access.JTI,
		// The browser sent the cookie of the session being revoked.
		RefreshToken: first.RefreshToken,
	})
	require.NoError(t, err)
	require.True(t, result.CurrentSessionRevoked)

	// The access token of that device is denylisted for the gateway.
	require.Contains(t, h.revocation.denied, first.Access.JTI)

	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: first.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)

	// The other device keeps working.
	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: second.RefreshToken})
	require.NoError(t, err)
}

// Revoking another device must not denylist the caller's own access token.
func TestRevokeOtherSessionKeepsTheCallerSignedIn(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	current := h.login(t)
	other := h.login(t)

	result, err := h.sessions.RevokeSession(context.Background(), service.RevokeSessionCommand{
		AccountID:    account.ID,
		SessionID:    other.SessionID,
		TokenJti:     current.Access.JTI,
		RefreshToken: current.RefreshToken,
	})
	require.NoError(t, err)
	require.False(t, result.CurrentSessionRevoked)
	require.NotContains(t, h.revocation.denied, current.Access.JTI)
}

// Revoking twice is not an error: the operation is idempotent.
func TestRevokeSessionIsIdempotent(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	cmd := service.RevokeSessionCommand{AccountID: account.ID, SessionID: login.SessionID}
	_, err := h.sessions.RevokeSession(ctx, cmd)
	require.NoError(t, err)
	_, err = h.sessions.RevokeSession(ctx, cmd)
	require.NoError(t, err)
}

// A session of another account is reported as not found rather than forbidden,
// so the endpoint cannot be used to probe other accounts.
func TestRevokeSessionRejectsAForeignSession(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	_, err := h.sessions.RevokeSession(context.Background(), service.RevokeSessionCommand{
		AccountID: account.ID,
		SessionID: uuid.New(),
	})
	require.ErrorIs(t, err, apperr.ErrSessionNotFound)
}

// Logout-all revokes every session and writes the user-wide marker the gateway
// compares against iat.
func TestRevokeAllSessionsWritesTheRevocationMarker(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	first := h.login(t)
	second := h.login(t)
	ctx := context.Background()

	require.NoError(t, h.sessions.RevokeAllSessions(ctx, account.ID))

	require.Equal(t, h.clock.Now(), h.revocation.markers[account.ID])
	for _, token := range []string{first.RefreshToken, second.RefreshToken} {
		_, err := h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: token})
		require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
	}
}

// A Redis failure must fail the request instead of reporting a revocation that
// never reached the gateway.
func TestRevokeAllSessionsFailsClosedWhenRedisIsDown(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	h.login(t)
	h.revocation.failWith = apperr.ErrDependencyUnavailable

	err := h.sessions.RevokeAllSessions(context.Background(), account.ID)
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)
}

func TestListSessionsRejectsAMalformedCursor(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	_, _, err := h.sessions.ListSessions(context.Background(), account.ID, "not-a-cursor", 50)
	require.ErrorIs(t, err, apperr.ErrValidation)
}

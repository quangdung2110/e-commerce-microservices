package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// WF-AUTH-002: a valid credential opens a session and returns a token pair.
func TestLoginIssuesATokenPair(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	result := h.login(t)

	require.NotEmpty(t, result.Access.Token)
	require.NotEmpty(t, result.RefreshToken)
	require.NotEqual(t, result.Access.Token, result.RefreshToken)
	require.Equal(t, 900, result.Access.ExpiresIn())

	// The session id is the stable family id, not the id of a token row.
	sessions, _, err := h.tokens.ListSessions(context.Background(), account.ID, nil, 10)
	require.NoError(t, err)
	require.Len(t, sessions, 1)
	require.Equal(t, result.SessionID, sessions[0].ID)
}

func TestLoginRejectsAWrongPassword(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)

	_, err := h.auth.Login(context.Background(), service.LoginCommand{
		Email:    testEmail,
		Password: "wrong password here",
	})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
}

// An unknown address reports the same error as a wrong password.
func TestLoginHidesUnknownAccounts(t *testing.T) {
	h := newHarness(t)

	_, err := h.auth.Login(context.Background(), service.LoginCommand{
		Email:    "nobody@example.com",
		Password: testPassword,
	})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
}

func TestLoginRejectsAnUnverifiedAccount(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: testPassword})
	require.ErrorIs(t, err, apperr.ErrAccountNotVerified)
}

// AUTH-BR-004: the configured number of failures locks the account and notifies
// its owner.
func TestLoginLocksTheAccountAtTheThreshold(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 2; i++ {
		_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
		require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
	}

	// The third failure reaches the threshold of the test policy.
	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	require.ErrorIs(t, err, apperr.ErrAccountLocked)
	require.Equal(t, 15*time.Minute, apperr.From(err).RetryAfter)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountLocked, stored.Status)
	require.NotNil(t, stored.LockedUntil)
	require.True(t, h.publisher.has(event.TypeUserAccountLocked))

	// A correct password does not help while the lock holds.
	_, err = h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: testPassword})
	require.ErrorIs(t, err, apperr.ErrAccountLocked)
}

func TestLoginResetsTheFailureCounterOnSuccess(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)

	h.login(t)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Zero(t, stored.FailedLoginCount)
	require.Nil(t, stored.LastFailedLoginAt)
}

// A lock that has run out is lifted on the next attempt, without a second
// unlock notification: the worker owns that event.
func TestLoginUnlocksAfterTheLockWindow(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		_, _ = h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	}
	h.clock.Advance(16 * time.Minute)

	result := h.login(t)
	require.NotEmpty(t, result.Access.Token)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountActive, stored.Status)
	require.Nil(t, stored.LockedUntil)
	require.False(t, h.publisher.has(event.TypeUserUnlocked))
}

// AUTH-BR-009: no session or device quota, so a second device does not evict a
// first one.
func TestLoginDoesNotLimitConcurrentSessions(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	first := h.login(t)
	second := h.login(t)
	require.NotEqual(t, first.SessionID, second.SessionID)

	sessions, _, err := h.tokens.ListSessions(context.Background(), account.ID, nil, 10)
	require.NoError(t, err)
	require.Len(t, sessions, 2)
}

func TestLoginRejectsWhenTheAccountRateLimitIsSpent(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	h.limiter.counts["login:account|owner@example.com"] = 100

	_, err := h.auth.Login(context.Background(), service.LoginCommand{
		Email:    testEmail,
		Password: testPassword,
	})
	require.ErrorIs(t, err, apperr.ErrRateLimited)
}

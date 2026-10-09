package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
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

	// A correct password does not help while the lock holds, and the lock is only
	// disclosed to a caller who proved the password.
	_, err = h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: testPassword})
	require.ErrorIs(t, err, apperr.ErrAccountLocked)
}

// The state of an account is not disclosed to a caller who does not know the
// password: a wrong password against a locked account is the same 401 as against
// an unknown address, and it neither extends the lock nor counts as a failure.
func TestLoginDoesNotRevealALockedAccountToAWrongPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		_, _ = h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	}
	locked := mustGet(t, h, account.ID)
	require.Equal(t, vo.AccountLocked, locked.Status)

	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "still wrong password"})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
	require.NotErrorIs(t, err, apperr.ErrAccountLocked)

	after := mustGet(t, h, account.ID)
	require.Equal(t, locked.FailedLoginCount, after.FailedLoginCount, "a refused attempt is not counted")
	require.Equal(t, locked.LockedUntil, after.LockedUntil, "the lock is not extended")
}

// Likewise a pending account is reported (403) only to the owner of the password.
func TestLoginDoesNotRevealAPendingAccountToAWrongPassword(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
	require.NotErrorIs(t, err, apperr.ErrAccountNotVerified)
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

// A lock that has run out is lifted on the next successful attempt, and the
// owner is told once: the account is ACTIVE afterwards, so the auto-unlock
// worker would never see it and the notice would otherwise be lost.
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
	require.Equal(t, 1, countOf(h.publisher.types(), event.TypeUserUnlocked))
	require.NotNil(t, h.deliveries.byTemplate(constants.TemplateAccountUnlocked))

	// The worker finds nothing left to unlock and does not announce it again.
	unlocked, err := h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, unlocked)
	require.Equal(t, 1, countOf(h.publisher.types(), event.TypeUserUnlocked))
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

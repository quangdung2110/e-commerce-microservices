// Package entity_test covers the rules the Account aggregate enforces on its
// own, independently of any adapter.
package entity_test

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

var now = time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)

func newTestAccount(t *testing.T) *entity.Account {
	t.Helper()
	email, err := vo.NewEmail("owner@example.com")
	require.NoError(t, err)
	return entity.NewAccount(email, "hashed:password", now)
}

var policy = entity.LockoutPolicy{
	MaxFailedAttempts: 3,
	LockDuration:      15 * time.Minute,
	CounterResetAfter: time.Hour,
}

func TestNewAccountStartsPendingWithTheUserRole(t *testing.T) {
	account := newTestAccount(t)

	require.Equal(t, vo.AccountPendingVerification, account.Status)
	require.Equal(t, []vo.Role{vo.RoleUser}, account.Roles)
	require.False(t, account.IsActive())
}

func TestActivateOnlyWorksOnce(t *testing.T) {
	account := newTestAccount(t)

	require.NoError(t, account.Activate(now))
	require.True(t, account.IsActive())

	err := account.Activate(now)
	require.ErrorIs(t, err, apperr.ErrAccountNotActive)
}

// AUTH-BR-001: only an ACTIVE account may authenticate, and each other state has
// its own error.
func TestEnsureCanAuthenticateByState(t *testing.T) {
	pending := newTestAccount(t)
	require.ErrorIs(t, pending.EnsureCanAuthenticate(now), apperr.ErrAccountNotVerified)

	active := newTestAccount(t)
	require.NoError(t, active.Activate(now))
	require.NoError(t, active.EnsureCanAuthenticate(now))

	deleted := newTestAccount(t)
	require.NoError(t, deleted.Activate(now))
	require.NoError(t, deleted.MarkDeleted(vo.DeletionGDPRRequest, now))
	require.ErrorIs(t, deleted.EnsureCanAuthenticate(now), apperr.ErrInvalidCredentials)
}

// AUTH-BR-004: the threshold locks the account, and the lock reports how long it
// still holds.
func TestRegisterFailedLoginLocksAtTheThreshold(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.Activate(now))

	require.False(t, account.RegisterFailedLogin(now, policy))
	require.False(t, account.RegisterFailedLogin(now, policy))
	require.True(t, account.RegisterFailedLogin(now, policy))

	require.Equal(t, vo.AccountLocked, account.Status)
	require.NotNil(t, account.LockedUntil)
	require.Equal(t, now.Add(15*time.Minute), *account.LockedUntil)

	err := account.EnsureCanAuthenticate(now)
	require.ErrorIs(t, err, apperr.ErrAccountLocked)
	require.Equal(t, 15*time.Minute, apperr.From(err).RetryAfter)
}

// A stale streak does not add up with a new mistake months later.
func TestRegisterFailedLoginForgetsAStaleStreak(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.Activate(now))

	require.False(t, account.RegisterFailedLogin(now, policy))
	require.False(t, account.RegisterFailedLogin(now, policy))

	later := now.Add(2 * time.Hour)
	require.False(t, account.RegisterFailedLogin(later, policy))
	require.Equal(t, 1, account.FailedLoginCount)
	require.Equal(t, vo.AccountActive, account.Status)
}

func TestUnlockIfExpiredWaitsForTheWindow(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.Activate(now))
	for i := 0; i < 3; i++ {
		account.RegisterFailedLogin(now, policy)
	}

	require.False(t, account.UnlockIfExpired(now.Add(10*time.Minute)))
	require.True(t, account.UnlockIfExpired(now.Add(16*time.Minute)))

	require.Equal(t, vo.AccountActive, account.Status)
	require.Nil(t, account.LockedUntil)
	require.Zero(t, account.FailedLoginCount)

	// A second call has nothing left to do.
	require.False(t, account.UnlockIfExpired(now.Add(20*time.Minute)))
}

// AUTH-BR-007: deletion anonymizes the identity and schedules the hard delete
// exactly 90 days later. The schema enforces the same arithmetic.
func TestMarkDeletedAnonymizesAndSchedules(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.Activate(now))

	require.NoError(t, account.MarkDeleted(vo.DeletionAdminDeletion, now))

	require.Equal(t, vo.AccountDeleted, account.Status)
	require.True(t, account.Email.IsZero())
	require.Empty(t, account.PasswordHash)
	require.Nil(t, account.LockedUntil)
	require.NotNil(t, account.DeletedAt)
	require.NotNil(t, account.HardDeleteAfter)
	require.Equal(t, now.AddDate(0, 0, 90), *account.HardDeleteAfter)
	require.Equal(t, vo.DeletionAdminDeletion, *account.DeletionReason)
}

func TestMarkDeletedIsTerminal(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.MarkDeleted(vo.DeletionGDPRRequest, now))

	err := account.MarkDeleted(vo.DeletionGDPRRequest, now.Add(time.Hour))
	require.ErrorIs(t, err, apperr.ErrAccountNotActive)
	require.Equal(t, now.AddDate(0, 0, 90), *account.HardDeleteAfter, "the retention window is not extended")
}

// A locked account keeps a lock timestamp; deleting it must clear the lock so
// the state stays consistent with the schema constraint.
func TestMarkDeletedClearsTheLock(t *testing.T) {
	account := newTestAccount(t)
	require.NoError(t, account.Activate(now))
	for i := 0; i < 3; i++ {
		account.RegisterFailedLogin(now, policy)
	}
	require.Equal(t, vo.AccountLocked, account.Status)

	require.NoError(t, account.MarkDeleted(vo.DeletionGDPRRequest, now))
	require.Nil(t, account.LockedUntil)
}

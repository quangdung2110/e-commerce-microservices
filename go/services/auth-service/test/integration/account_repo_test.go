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

func newAccount(t *testing.T, address string, now time.Time) *entity.Account {
	t.Helper()
	email, err := vo.NewEmail(address)
	require.NoError(t, err)
	return entity.NewAccount(email, "hashed:password", now)
}

func TestAccountRepositoryRoundTrip(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "Owner@Example.com", now)
	require.NoError(t, accounts.Create(ctx, account))

	loaded, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	// The address is stored as typed and matched on its normalized form.
	require.Equal(t, "Owner@Example.com", loaded.Email.String())
	require.Equal(t, "owner@example.com", loaded.Email.Normalized())
	require.Equal(t, vo.AccountPendingVerification, loaded.Status)
	require.Equal(t, []vo.Role{vo.RoleUser}, loaded.Roles)

	byEmail, err := accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, account.ID, byEmail.ID)

	exists, err := accounts.ExistsByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.True(t, exists)

	exists, err = accounts.ExistsByNormalizedEmail(ctx, "someone.else@example.com")
	require.NoError(t, err)
	require.False(t, exists)
}

func TestAccountRepositoryReportsUnknownAccount(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)

	_, err := accounts.GetByID(ctx, uuid.New())
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)

	_, err = accounts.GetByNormalizedEmail(ctx, "nobody@example.com")
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
}

// The unique identity index must surface as a domain error the API can map to
// 409, not as an opaque driver failure.
func TestAccountRepositoryRejectsADuplicateIdentity(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC()

	require.NoError(t, accounts.Create(ctx, newAccount(t, "owner@example.com", now)))

	// A different case is the same identity.
	err := accounts.Create(ctx, newAccount(t, "OWNER@example.com", now))
	require.ErrorIs(t, err, apperr.ErrEmailAlreadyRegistered)
}

func TestAccountRepositoryUpdatesState(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))

	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))

	loaded, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountActive, loaded.Status)

	// An email change keeps the normalized column consistent with the address.
	newEmail, err := vo.NewEmail("New.Owner@Example.com")
	require.NoError(t, err)
	require.NoError(t, loaded.ChangeEmail(newEmail, now))
	require.NoError(t, accounts.Update(ctx, loaded))

	moved, err := accounts.GetByNormalizedEmail(ctx, "new.owner@example.com")
	require.NoError(t, err)
	require.Equal(t, account.ID, moved.ID)
}

func TestAccountRepositoryUpdateRejectsATakenAddress(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC()

	first := newAccount(t, "first@example.com", now)
	second := newAccount(t, "second@example.com", now)
	require.NoError(t, accounts.Create(ctx, first))
	require.NoError(t, accounts.Create(ctx, second))
	require.NoError(t, second.Activate(now))
	require.NoError(t, accounts.Update(ctx, second))

	taken, err := vo.NewEmail("first@example.com")
	require.NoError(t, err)
	require.NoError(t, second.ChangeEmail(taken, now))

	require.ErrorIs(t, accounts.Update(ctx, second), apperr.ErrEmailAlreadyRegistered)
}

func TestAccountRepositoryListsExpiredLocks(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC()

	locked := newAccount(t, "locked@example.com", now)
	require.NoError(t, accounts.Create(ctx, locked))
	require.NoError(t, locked.Activate(now))
	require.True(t, locked.RegisterFailedLogin(now, entity.LockoutPolicy{
		MaxFailedAttempts: 1, LockDuration: 15 * time.Minute,
	}))
	require.NoError(t, accounts.Update(ctx, locked))

	// The window has not elapsed yet.
	expired, err := accounts.ListExpiredLocks(ctx, now, 10)
	require.NoError(t, err)
	require.Empty(t, expired)

	expired, err = accounts.ListExpiredLocks(ctx, now.Add(16*time.Minute), 10)
	require.NoError(t, err)
	require.Len(t, expired, 1)
	require.Equal(t, locked.ID, expired[0].ID)
}

// AUTH-BR-007: the schema enforces the exact 90-day window, so the timestamps
// computed in Go must match the CHECK constraint. The session revocation and the
// anonymization share one transaction.
func TestAccountDeletionSatisfiesTheRetentionConstraint(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	tokens := postgres.NewRefreshTokenRepository(testPool)
	txManager := postgres.NewTxManager(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))
	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))

	err := txManager.WithinTx(ctx, func(txCtx context.Context) error {
		if _, err := tokens.RevokeAllForAccount(txCtx, account.ID, now); err != nil {
			return err
		}
		if err := account.MarkDeleted(vo.DeletionGDPRRequest, now); err != nil {
			return err
		}
		return accounts.Update(txCtx, account)
	})
	require.NoError(t, err)

	stored, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountDeleted, stored.Status)
	require.True(t, stored.Email.IsZero(), "the identity is anonymized immediately")
	require.Empty(t, stored.PasswordHash)
	require.NotNil(t, stored.HardDeleteAfter)
	require.Equal(t, now.AddDate(0, 0, 90).Unix(), stored.HardDeleteAfter.Unix())
	require.Equal(t, vo.DeletionGDPRRequest, *stored.DeletionReason)

	// The address is free again right away.
	require.NoError(t, accounts.Create(ctx, newAccount(t, "owner@example.com", now)))
}

// A failing unit of work must leave nothing behind.
func TestTransactionRollsBack(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	txManager := postgres.NewTxManager(testPool)
	now := time.Now().UTC()

	sentinel := apperr.ErrInternal.WithDetail("deliberate failure")
	err := txManager.WithinTx(ctx, func(txCtx context.Context) error {
		if err := accounts.Create(txCtx, newAccount(t, "rollback@example.com", now)); err != nil {
			return err
		}
		return sentinel
	})
	require.ErrorIs(t, err, sentinel)

	_, err = accounts.GetByNormalizedEmail(ctx, "rollback@example.com")
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
}

func TestHardDeleteRemovesDueAccountsOnly(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC()

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))
	require.NoError(t, account.MarkDeleted(vo.DeletionAdminDeletion, now))
	require.NoError(t, accounts.Update(ctx, account))

	due, err := accounts.ListDueForHardDelete(ctx, now.AddDate(0, 0, 89), 10)
	require.NoError(t, err)
	require.Empty(t, due, "the retention window has not elapsed")

	due, err = accounts.ListDueForHardDelete(ctx, now.AddDate(0, 0, 91), 10)
	require.NoError(t, err)
	require.Equal(t, []uuid.UUID{account.ID}, due)

	deleted, err := accounts.HardDelete(ctx, due)
	require.NoError(t, err)
	require.Equal(t, int64(1), deleted)

	_, err = accounts.GetByID(ctx, account.ID)
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)

	// Deleting nothing is not an error.
	deleted, err = accounts.HardDelete(ctx, nil)
	require.NoError(t, err)
	require.Zero(t, deleted)
}

// Update is a compare-and-set on the version the copy was loaded at. A copy that
// was read before another writer saved must not write its stale password hash,
// status or email back over the newer row (lost update).
func TestAccountRepositoryUpdateRefusesAStaleCopy(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))
	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))

	// A slow login and a password change both start from the same row.
	login, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	change, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)

	require.NoError(t, change.SetPasswordHash("hashed:new password", now))
	require.NoError(t, accounts.Update(ctx, change))

	login.RegisterFailedLogin(now, entity.LockoutPolicy{MaxFailedAttempts: 5, LockDuration: time.Minute})
	require.ErrorIs(t, accounts.Update(ctx, login), apperr.ErrConcurrentUpdate)

	stored, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, "hashed:new password", stored.PasswordHash, "the stale copy wrote nothing")
	require.Zero(t, stored.FailedLoginCount)
}

// A stale copy must not bring a deleted account back, with its email.
func TestAccountRepositoryStaleUpdateCannotResurrectADeletedAccount(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))
	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))

	stale, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)

	require.NoError(t, account.MarkDeleted(vo.DeletionGDPRRequest, now))
	require.NoError(t, accounts.Update(ctx, account))

	stale.FailedLoginCount = 1
	require.ErrorIs(t, accounts.Update(ctx, stale), apperr.ErrConcurrentUpdate)

	stored, err := accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountDeleted, stored.Status)
	require.True(t, stored.Email.IsZero())
}

// A copy that is saved keeps the version of the row, so it can be saved again
// without a reload; the missing row is still told apart from a stale one.
func TestAccountRepositoryUpdateAdvancesTheRevision(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account := newAccount(t, "owner@example.com", now)
	require.NoError(t, accounts.Create(ctx, account))
	first := account.Revision
	require.False(t, first.IsZero(), "Create reports the version of the new row")

	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))
	require.True(t, account.Revision.After(first), "the trigger moved updated_at")

	account.FailedLoginCount = 2
	require.NoError(t, accounts.Update(ctx, account), "the same copy saves again")

	ghost := newAccount(t, "ghost@example.com", now)
	ghost.Revision = now
	require.ErrorIs(t, accounts.Update(ctx, ghost), apperr.ErrAccountNotFound)
}

package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// WF-AUTH-006 and AUTH-BR-007: deleting anonymizes immediately and schedules the
// hard delete exactly 90 days later.
func TestDeleteOwnAccountAnonymizesAndRevokes(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	result, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.NoError(t, err)
	require.Equal(t, vo.AccountDeleted.String(), result.Status)
	require.Equal(t, h.clock.Now().AddDate(0, 0, 90), result.HardDeleteAfter)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountDeleted, stored.Status)
	require.True(t, stored.Email.IsZero(), "the identity must be anonymized immediately")
	require.Empty(t, stored.PasswordHash)
	require.NotNil(t, stored.DeletionReason)
	require.Equal(t, vo.DeletionGDPRRequest, *stored.DeletionReason)

	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
	require.Equal(t, h.clock.Now(), h.revocation.markers[account.ID])

	require.True(t, h.publisher.has(event.TypeUserDeleted))
}

func TestDeleteOwnAccountRejectsAWrongPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	_, err := h.deletions.DeleteOwn(context.Background(), account.ID, "not the password")
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
}

// DELETED is terminal: a second request is a conflict, not a new retention
// window.
func TestDeleteOwnAccountIsTerminal(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	_, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.NoError(t, err)

	_, err = h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.ErrorIs(t, err, apperr.ErrAccountNotActive)
}

// The address is free again as soon as the account is anonymized.
func TestDeletedAddressCanBeRegisteredAgain(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	_, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.NoError(t, err)

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	fresh, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.NotEqual(t, account.ID, fresh.ID, "the old account is not restored, a new one is created")
}

func TestDeleteByAdminUsesTheAdminReason(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	result, err := h.deletions.DeleteByAdmin(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountDeleted.String(), result.Status)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.DeletionAdminDeletion, *stored.DeletionReason)
}

func TestDeleteByAdminRejectsAnUnknownAccount(t *testing.T) {
	h := newHarness(t)

	_, err := h.deletions.DeleteByAdmin(context.Background(), uuid.New())
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
}

// The compliance worker removes the row only once the window has elapsed.
func TestHardDeleteWaitsForTheRetentionWindow(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	_, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.NoError(t, err)

	h.clock.Advance(89 * 24 * time.Hour)
	deleted, err := h.maintenance.HardDeleteDue(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, deleted)

	h.clock.Advance(2 * 24 * time.Hour)
	deleted, err = h.maintenance.HardDeleteDue(ctx, 100)
	require.NoError(t, err)
	require.Equal(t, int64(1), deleted)

	_, err = h.accounts.GetByID(ctx, account.ID)
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
}

// The auto-unlock worker returns an expired lock to ACTIVE and notifies once.
func TestUnlockExpiredReturnsAccountsToActive(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		_, _ = h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	}
	require.Equal(t, vo.AccountLocked, mustGet(t, h, account.ID).Status)

	// Still locked: nothing to do yet.
	unlocked, err := h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, unlocked)

	h.clock.Advance(16 * time.Minute)
	unlocked, err = h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Equal(t, 1, unlocked)

	stored := mustGet(t, h, account.ID)
	require.Equal(t, vo.AccountActive, stored.Status)
	require.Nil(t, stored.LockedUntil)
	require.Zero(t, stored.FailedLoginCount)

	require.True(t, h.publisher.has(event.TypeUserUnlocked))
	require.NotNil(t, h.deliveries.byTemplate(constants.TemplateAccountUnlocked))

	// A second pass finds nothing and notifies nobody again.
	unlocked, err = h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, unlocked)
}

func mustGet(t *testing.T, h *harness, id uuid.UUID) *entity.Account {
	t.Helper()
	account, err := h.accounts.GetByID(context.Background(), id)
	require.NoError(t, err)
	return account
}

package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// Lost-update protection. An account is read, a slow step runs (an Argon2
// verification, a Redis round trip) and the row is written back. Writing the
// whole row from the stale copy used to undo whatever committed in between: a new
// password hash, a deletion (status DELETED and the anonymized email). The
// repository now saves only over the version it loaded, and the services reload
// and re-apply their change when they lose.
//
// The fake repository returns independent copies and compares versions like the
// PostgreSQL adapter; afterRead commits a competing change at the exact moment
// the code under test sits between its read and its write.

func countOf(types []string, eventType string) int {
	n := 0
	for _, got := range types {
		if got == eventType {
			n++
		}
	}
	return n
}

// competingWriteOnce makes the next read of an account be followed by another
// writer committing change before the reader can save.
func (h *harness) competingWriteOnce(change func(stored *entity.Account)) {
	fired := false
	h.accounts.afterRead = func(stored *entity.Account) {
		if fired {
			return
		}
		fired = true
		change(stored)
		stored.Revision = stored.Revision.Add(time.Microsecond)
		stored.UpdatedAt = stored.Revision
	}
}

func failedAttempt(h *harness, ctx context.Context) error {
	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	return err
}

// A failed login that raced a password change must not write the old hash back.
func TestFailedLoginDoesNotRestoreAnOldPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.competingWriteOnce(func(stored *entity.Account) { stored.PasswordHash = "hashed:" + testNewPassword })
	require.ErrorIs(t, failedAttempt(h, ctx), apperr.ErrInvalidCredentials)

	stored := mustGet(t, h, account.ID)
	require.Equal(t, "hashed:"+testNewPassword, stored.PasswordHash, "the committed password change survives")
	require.Equal(t, 1, stored.FailedLoginCount, "the failure is still counted, on the current row")
	require.Equal(t, 1, h.accounts.conflicts)
}

// A failed login that raced a deletion must not bring the account back.
func TestFailedLoginDoesNotResurrectADeletedAccount(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.competingWriteOnce(func(stored *entity.Account) {
		require.NoError(t, stored.MarkDeleted(vo.DeletionGDPRRequest, h.clock.Now()))
	})
	require.ErrorIs(t, failedAttempt(h, ctx), apperr.ErrInvalidCredentials)

	stored := mustGet(t, h, account.ID)
	require.Equal(t, vo.AccountDeleted, stored.Status)
	require.True(t, stored.Email.IsZero(), "the anonymized identity must stay anonymized")
	require.Empty(t, stored.PasswordHash)
}

// Two failed logins that overlap are both counted, so parallel guessing cannot
// slip under the lockout threshold.
func TestOverlappingFailedLoginsAreBothCounted(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.competingWriteOnce(func(stored *entity.Account) {
		now := h.clock.Now()
		stored.FailedLoginCount = 1
		stored.LastFailedLoginAt = &now
	})
	require.ErrorIs(t, failedAttempt(h, ctx), apperr.ErrInvalidCredentials)

	require.Equal(t, 2, mustGet(t, h, account.ID).FailedLoginCount)
}

// A correct password that was verified against a hash that has since changed is
// no longer a correct password.
func TestLoginRejectsAPasswordChangedWhileItWasBeingVerified(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	// A recorded failure makes the successful login write the row (reset counter).
	require.ErrorIs(t, failedAttempt(h, ctx), apperr.ErrInvalidCredentials)

	h.competingWriteOnce(func(stored *entity.Account) { stored.PasswordHash = "hashed:" + testNewPassword })
	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: testPassword})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)

	require.Equal(t, "hashed:"+testNewPassword, mustGet(t, h, account.ID).PasswordHash)
}

// Losing the race once is invisible to the caller: the change is re-applied to
// the current row.
func TestChangePasswordSurvivesALostRace(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.competingWriteOnce(func(stored *entity.Account) {
		now := h.clock.Now()
		stored.FailedLoginCount = 1
		stored.LastFailedLoginAt = &now
	})
	require.NoError(t, h.passwords.ChangePassword(ctx, service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	}))

	stored := mustGet(t, h, account.ID)
	require.Equal(t, "hashed:"+testNewPassword, stored.PasswordHash)
	require.Equal(t, 1, stored.FailedLoginCount, "the competing change is kept")
	require.Equal(t, 1, h.accounts.conflicts)
	require.True(t, h.publisher.has(event.TypeUserPasswordChanged))
}

// Changing the password with a current password that another request has just
// replaced must fail instead of overwriting the newer credential.
func TestChangePasswordRejectsACredentialReplacedMeanwhile(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.competingWriteOnce(func(stored *entity.Account) { stored.PasswordHash = "hashed:somebody else's password" })
	err := h.passwords.ChangePassword(ctx, service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)

	require.Equal(t, "hashed:somebody else's password", mustGet(t, h, account.ID).PasswordHash)
	require.False(t, h.publisher.has(event.TypeUserPasswordChanged))
}

// An owner deletion whose password was replaced meanwhile is refused.
func TestDeleteOwnRejectsACredentialReplacedMeanwhile(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	h.competingWriteOnce(func(stored *entity.Account) { stored.PasswordHash = "hashed:somebody else's password" })
	_, err := h.deletions.DeleteOwn(context.Background(), account.ID, testPassword)
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)

	require.Equal(t, vo.AccountActive, mustGet(t, h, account.ID).Status)
	require.False(t, h.publisher.has(event.TypeUserDeleted))
}

// Two deletions that overlap do not restart the 90-day window or publish twice.
func TestOverlappingDeletionsDoNotRestartTheRetentionWindow(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	firstDeletedAt := h.clock.Now().Add(-48 * time.Hour)
	h.competingWriteOnce(func(stored *entity.Account) {
		require.NoError(t, stored.MarkDeleted(vo.DeletionGDPRRequest, firstDeletedAt))
	})
	_, err := h.deletions.DeleteByAdmin(ctx, account.ID)
	require.ErrorIs(t, err, apperr.ErrAccountNotActive)

	stored := mustGet(t, h, account.ID)
	require.Equal(t, firstDeletedAt.AddDate(0, 0, 90), *stored.HardDeleteAfter, "the first window stands")
	require.False(t, h.publisher.has(event.TypeUserDeleted), "only the request that deleted publishes")
}

// An email-change code that was already consumed still completes when the row
// moved on between the read and the save.
func TestConfirmEmailChangeSurvivesALostRace(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))
	code := h.otpCode(t, constants.TemplateEmailChangeVerification)

	h.competingWriteOnce(func(stored *entity.Account) {
		now := h.clock.Now()
		stored.FailedLoginCount = 1
		stored.LastFailedLoginAt = &now
	})
	require.NoError(t, h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  newEmail,
		OTP:       code,
	}))

	require.Equal(t, newEmail, mustGet(t, h, account.ID).Email.String())
	require.True(t, h.publisher.has(event.TypeUserEmailChanged))
}

// Registration verification consumes its code before the save, so it too is
// re-applied rather than failed.
func TestVerifyEmailSurvivesALostRace(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))
	code := h.otpCode(t, constants.TemplateRegistrationVerification)

	h.competingWriteOnce(func(stored *entity.Account) { stored.PasswordChangedAt = stored.PasswordChangedAt.Add(time.Second) })
	require.NoError(t, h.registration.VerifyEmail(ctx, testEmail, code))

	account, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountActive, account.Status)
	require.True(t, h.publisher.has(event.TypeUserVerifiedSuccess))
}

// A writer that keeps losing gives up with a retryable error instead of looping.
func TestUpdateGivesUpAfterRepeatedLostRaces(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	ctx := context.Background()

	h.accounts.afterRead = func(stored *entity.Account) {
		stored.Revision = stored.Revision.Add(time.Microsecond)
		stored.UpdatedAt = stored.Revision
	}
	err := failedAttempt(h, ctx)
	require.ErrorIs(t, err, apperr.ErrConcurrentUpdate)
	require.True(t, apperr.From(err).Retryable)
	require.Equal(t, 4, h.accounts.conflicts)
}

// Refresh lifts an expired lock and announces it, exactly like login.
func TestRefreshUnlocksAnExpiredLockAndAnnouncesIt(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		_ = failedAttempt(h, ctx)
	}
	require.Equal(t, vo.AccountLocked, mustGet(t, h, account.ID).Status)
	h.clock.Advance(16 * time.Minute)

	_, err := h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.NoError(t, err)

	require.Equal(t, vo.AccountActive, mustGet(t, h, account.ID).Status)
	require.Equal(t, 1, countOf(h.publisher.types(), event.TypeUserUnlocked))

	unlocked, err := h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, unlocked, "the worker must not announce an unlock refresh already did")
	require.Equal(t, 1, countOf(h.publisher.types(), event.TypeUserUnlocked))
}

// When the worker loses the unlock race to an inline unlock it stays silent: the
// winner already told the owner.
func TestUnlockWorkerStaysSilentWhenLoginUnlockedFirst(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		_ = failedAttempt(h, ctx)
	}
	h.clock.Advance(16 * time.Minute)

	// Between the worker listing the account and saving it, a login unlocks it.
	h.competingWriteOnce(func(stored *entity.Account) { _ = stored.UnlockIfExpired(h.clock.Now()) })
	unlocked, err := h.maintenance.UnlockExpired(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, unlocked)
	require.Equal(t, 0, countOf(h.publisher.types(), event.TypeUserUnlocked),
		"the worker did not write the row, so it does not announce it")
	require.Equal(t, vo.AccountActive, mustGet(t, h, account.ID).Status)
}

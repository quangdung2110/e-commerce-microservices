package service_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// ADR-006: once the database change is committed it cannot be repeated, so the
// event that tells the rest of the system about it must not depend on the Redis
// revocation marker succeeding. The marker failure is still reported to the
// caller (ADR-001, ADR-004).

func TestDeleteOwnAccountStillPublishesUserDeletedWhenTheMarkerFails(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	h.revocation.failWith = apperr.ErrDependencyUnavailable
	_, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	// The deletion is committed...
	require.Equal(t, vo.AccountDeleted, mustGet(t, h, account.ID).Status)
	// ...a retry is a conflict, so the event cannot be produced later...
	h.revocation.failWith = nil
	_, err = h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.ErrorIs(t, err, apperr.ErrAccountNotActive)
	// ...which is why it was published on the first attempt.
	require.True(t, h.publisher.has(event.TypeUserDeleted),
		"USER_DELETED must be published even though the revocation marker failed")
}

func TestDeleteByAdminStillPublishesUserDeletedWhenTheMarkerFails(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	h.revocation.failWith = apperr.ErrDependencyUnavailable
	_, err := h.deletions.DeleteByAdmin(context.Background(), account.ID)
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	require.True(t, h.publisher.has(event.TypeUserDeleted))
}

func TestConfirmEmailChangeStillWarnsThePreviousAddressWhenRevocationFails(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))
	code := h.otpCode(t, constants.TemplateEmailChangeVerification)

	h.revocation.failWith = apperr.ErrDependencyUnavailable
	err := h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  newEmail,
		OTP:       code,
	})
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	require.Equal(t, newEmail, mustGet(t, h, account.ID).Email.String(), "the identity already moved")
	require.True(t, h.publisher.has(event.TypeUserEmailChanged),
		"the owner of the previous address must still be warned")
	notice := h.deliveries.byTemplate(constants.TemplateEmailChanged)
	require.NotNil(t, notice)
	require.Equal(t, testEmail, notice.RecipientEmail)
}

func TestChangePasswordStillNotifiesTheOwnerWhenRevocationFails(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	h.revocation.failWith = apperr.ErrDependencyUnavailable
	err := h.passwords.ChangePassword(context.Background(), service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	require.Equal(t, "hashed:"+testNewPassword, mustGet(t, h, account.ID).PasswordHash)
	require.True(t, h.publisher.has(event.TypeUserPasswordChanged))
}

func TestCompleteResetStillInvalidatesAndNotifiesWhenRevocationFails(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	// The fake keeps both links (the Redis adapter keeps only the newest), so
	// emptying the store proves the invalidation step ran after the failure.
	require.NoError(t, h.passwords.RequestReset(ctx, testEmail))
	first := resetTokenFrom(t, h)
	require.NoError(t, h.passwords.RequestReset(ctx, testEmail))

	h.revocation.failWith = apperr.ErrDependencyUnavailable
	err := h.passwords.CompleteReset(ctx, service.CompleteResetCommand{
		ResetToken:      first,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	require.Equal(t, "hashed:"+testNewPassword, mustGet(t, h, account.ID).PasswordHash)
	require.True(t, h.publisher.has(event.TypeUserPasswordChanged))
	require.Empty(t, h.resets.tokens, "no reset link may survive a committed password change")
}

// A password reset must not reveal whether an address has an account: past the
// per-account budget the answer is the same success an unknown address gets.
func TestRequestResetStaysSilentAfterThePerAccountBudget(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)
	ctx := context.Background()

	// The harness budget is 100 requests per window.
	for i := 0; i < 100; i++ {
		require.NoError(t, h.passwords.RequestReset(ctx, testEmail))
	}
	sent := len(h.publisher.types())

	require.NoError(t, h.passwords.RequestReset(ctx, testEmail),
		"the answer must not differ from an unknown address")
	require.Equal(t, sent, len(h.publisher.types()), "nothing is sent past the budget")
}

package service_test

import (
	"context"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// WF-AUTH-005 and AUTH-BR-003: a password change revokes every session.
func TestChangePasswordRevokesEverySession(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	require.NoError(t, h.passwords.ChangePassword(ctx, service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	}))

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, "hashed:"+testNewPassword, stored.PasswordHash)

	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
	require.Equal(t, h.clock.Now(), h.revocation.markers[account.ID])

	require.True(t, h.publisher.has(event.TypeUserPasswordChanged))
	require.NotNil(t, h.deliveries.byTemplate(constants.TemplatePasswordChanged))
}

func TestChangePasswordRejectsAWrongCurrentPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	err := h.passwords.ChangePassword(context.Background(), service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: "not the password",
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
}

func TestChangePasswordRejectsReusingTheCurrentPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	err := h.passwords.ChangePassword(context.Background(), service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     testPassword,
		ConfirmPassword: testPassword,
	})
	require.ErrorIs(t, err, apperr.ErrPasswordPolicy)
}

func TestChangePasswordRejectsAPasswordContainingTheEmail(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	err := h.passwords.ChangePassword(context.Background(), service.ChangePasswordCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewPassword:     "owner-owner-owner",
		ConfirmPassword: "owner-owner-owner",
	})
	require.ErrorIs(t, err, apperr.ErrPasswordPolicy)
}

// The reset request answers the same way for an unknown address and never puts
// the link into the event.
func TestRequestResetHidesUnknownAccounts(t *testing.T) {
	h := newHarness(t)

	require.NoError(t, h.passwords.RequestReset(context.Background(), "nobody@example.com"))
	require.Empty(t, h.publisher.types())
}

func TestRequestResetStoresTheLinkOutsideTheEvent(t *testing.T) {
	h := newHarness(t)
	h.registerAndVerify(t)

	require.NoError(t, h.passwords.RequestReset(context.Background(), testEmail))

	require.True(t, h.publisher.has(event.TypePasswordResetRequested))
	delivery := h.deliveries.byTemplate(constants.TemplatePasswordReset)
	require.NotNil(t, delivery)

	resetURL := delivery.TemplateData["reset_url"]
	require.True(t, strings.HasPrefix(resetURL, "https://app.test/reset#token="))

	// The token lives in the fragment and in the delivery state only.
	for _, envelope := range h.publisher.events {
		data, ok := envelope.Data.(event.PasswordResetRequestedData)
		if !ok {
			continue
		}
		require.Equal(t, delivery.DeliveryID.String(), data.DeliveryID)
	}
}

// AUTH-BR-005: the reset token works exactly once.
func TestCompleteResetConsumesTheTokenOnce(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	require.NoError(t, h.passwords.RequestReset(ctx, testEmail))
	token := resetTokenFrom(t, h)

	require.NoError(t, h.passwords.CompleteReset(ctx, service.CompleteResetCommand{
		ResetToken:      token,
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	}))

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, "hashed:"+testNewPassword, stored.PasswordHash)

	// Sessions opened before the reset are gone.
	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)

	replayErr := h.passwords.CompleteReset(ctx, service.CompleteResetCommand{
		ResetToken:      token,
		NewPassword:     "yet another password",
		ConfirmPassword: "yet another password",
	})
	require.ErrorIs(t, replayErr, apperr.ErrResetTokenInvalid)
}

func TestCompleteResetRejectsAnUnknownToken(t *testing.T) {
	h := newHarness(t)

	err := h.passwords.CompleteReset(context.Background(), service.CompleteResetCommand{
		ResetToken:      strings.Repeat("a", 43),
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrResetTokenInvalid)
}

func TestCompleteResetRejectsATokenOfTheWrongShape(t *testing.T) {
	h := newHarness(t)

	err := h.passwords.CompleteReset(context.Background(), service.CompleteResetCommand{
		ResetToken:      "too-short",
		NewPassword:     testNewPassword,
		ConfirmPassword: testNewPassword,
	})
	require.ErrorIs(t, err, apperr.ErrValidation)
}

// resetTokenFrom recovers the plaintext token from the reset URL of the stored
// delivery, which is the only place it exists after issuance.
func resetTokenFrom(t *testing.T, h *harness) string {
	t.Helper()
	delivery := h.deliveries.byTemplate(constants.TemplatePasswordReset)
	require.NotNil(t, delivery)

	resetURL := delivery.TemplateData["reset_url"]
	index := strings.Index(resetURL, "#token=")
	require.GreaterOrEqual(t, index, 0)
	return resetURL[index+len("#token="):]
}

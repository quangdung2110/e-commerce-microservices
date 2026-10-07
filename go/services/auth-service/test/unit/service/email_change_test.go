package service_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

const newEmail = "new.owner@example.com"

// WF-AUTH-007: the request sends the code to the address being claimed and
// changes nothing yet.
func TestRequestEmailChangeSendsTheCodeToTheNewAddress(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))

	delivery := h.deliveries.byTemplate(constants.TemplateEmailChangeVerification)
	require.NotNil(t, delivery)
	require.Equal(t, newEmail, delivery.RecipientEmail)
	require.True(t, h.publisher.has(event.TypeUserOtpRequested))

	// The identity has not moved yet.
	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, testEmail, stored.Email.String())

	// The challenge is bound to the new address.
	challenge, ok := h.otps.issuedFor(constants.OtpPurposeEmailChange, account.ID)
	require.True(t, ok)
	require.Equal(t, newEmail, challenge.Subject)
}

func TestRequestEmailChangeRejectsAWrongPassword(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	err := h.emailChanges.Request(context.Background(), service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: "not the password",
		NewEmail:        newEmail,
	})
	require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
}

// AUTH-BR-008: the new address must be free system-wide.
func TestRequestEmailChangeRejectsATakenAddress(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           newEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	err := h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	})
	require.ErrorIs(t, err, apperr.ErrEmailAlreadyRegistered)
}

func TestRequestEmailChangeRejectsTheCurrentAddress(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	err := h.emailChanges.Request(context.Background(), service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        testEmail,
	})
	require.ErrorIs(t, err, apperr.ErrEmailAlreadyRegistered)
}

// The confirmation moves the identity, revokes every session and warns the
// previous address.
func TestConfirmEmailChangeMovesTheIdentityAndRevokesSessions(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	login := h.login(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))
	code := h.otpCode(t, constants.TemplateEmailChangeVerification)

	require.NoError(t, h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  newEmail,
		OTP:       code,
	}))

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, newEmail, stored.Email.String())

	_, err = h.sessions.Refresh(ctx, service.RefreshCommand{RefreshToken: login.RefreshToken})
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
	require.Equal(t, h.clock.Now(), h.revocation.markers[account.ID])

	require.True(t, h.publisher.has(event.TypeUserEmailChanged))
	notice := h.deliveries.byTemplate(constants.TemplateEmailChanged)
	require.NotNil(t, notice)
	require.Equal(t, testEmail, notice.RecipientEmail, "the warning goes to the previous address")
}

// A code issued for one address cannot confirm another.
func TestConfirmEmailChangeRejectsADifferentAddress(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))
	code := h.otpCode(t, constants.TemplateEmailChangeVerification)

	err := h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  "someone.else@example.com",
		OTP:       code,
	})
	require.ErrorIs(t, err, apperr.ErrOtpInvalid)

	stored, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, testEmail, stored.Email.String())
}

func TestConfirmEmailChangeRejectsAReplayedCode(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	require.NoError(t, h.emailChanges.Request(ctx, service.RequestEmailChangeCommand{
		AccountID:       account.ID,
		CurrentPassword: testPassword,
		NewEmail:        newEmail,
	}))
	code := h.otpCode(t, constants.TemplateEmailChangeVerification)

	require.NoError(t, h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  newEmail,
		OTP:       code,
	}))

	err := h.emailChanges.Confirm(ctx, service.ConfirmEmailChangeCommand{
		AccountID: account.ID,
		NewEmail:  newEmail,
		OTP:       code,
	})
	require.ErrorIs(t, err, apperr.ErrOtpInvalid)
}

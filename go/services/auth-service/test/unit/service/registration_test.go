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

// WF-AUTH-001: registration creates a pending account and asks Notification for
// a code, without leaking the code into the event.
func TestRegisterCreatesPendingAccountAndRequestsCode(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	account, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountPendingVerification, account.Status)
	require.Equal(t, []vo.Role{vo.RoleUser}, account.Roles)
	// The address is stored as typed, and matched on its normalized form.
	require.Equal(t, testEmail, account.Email.String())

	require.True(t, h.publisher.has(event.TypeUserOtpRequested))

	delivery := h.deliveries.byTemplate(constants.TemplateRegistrationVerification)
	require.NotNil(t, delivery)
	require.Equal(t, testEmail, delivery.RecipientEmail)
	require.Len(t, delivery.TemplateData["otp"], constants.OtpLength)

	// The event itself carries only the delivery id and identifiers.
	for _, envelope := range h.publisher.events {
		data, ok := envelope.Data.(event.UserOtpRequestedData)
		if !ok {
			continue
		}
		require.Equal(t, delivery.DeliveryID.String(), data.DeliveryID)
		require.Equal(t, constants.OtpPurposeRegistration, data.Purpose)
	}
}

func TestRegisterRejectsAnAlreadyRegisteredEmail(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	// The same address in a different case is the same identity.
	err := h.registration.Register(ctx, service.RegisterCommand{
		Email:           "owner@example.com",
		Password:        testPassword,
		ConfirmPassword: testPassword,
	})
	require.ErrorIs(t, err, apperr.ErrEmailAlreadyRegistered)
}

func TestRegisterRejectsAMismatchedConfirmation(t *testing.T) {
	h := newHarness(t)

	err := h.registration.Register(context.Background(), service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword + "!",
	})
	require.ErrorIs(t, err, apperr.ErrValidation)
}

func TestRegisterRejectsAShortPassword(t *testing.T) {
	h := newHarness(t)

	err := h.registration.Register(context.Background(), service.RegisterCommand{
		Email:           testEmail,
		Password:        "short",
		ConfirmPassword: "short",
	})
	require.ErrorIs(t, err, apperr.ErrValidation)
}

// AUTH-BR-005: a code works once. The replay must not activate anything again or
// emit a second identity event.
func TestVerifyEmailActivatesOnceAndRejectsAReplay(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))
	code := h.otpCode(t, constants.TemplateRegistrationVerification)

	require.NoError(t, h.registration.VerifyEmail(ctx, testEmail, code))

	account, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountActive, account.Status)
	require.True(t, h.publisher.has(event.TypeUserVerifiedSuccess))

	replayErr := h.registration.VerifyEmail(ctx, testEmail, code)
	require.ErrorIs(t, replayErr, apperr.ErrAccountNotActive)

	verified := 0
	for _, published := range h.publisher.types() {
		if published == event.TypeUserVerifiedSuccess {
			verified++
		}
	}
	require.Equal(t, 1, verified, "a duplicate verification must not emit a second event")
}

func TestVerifyEmailRejectsAWrongCode(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))

	err := h.registration.VerifyEmail(ctx, testEmail, "000000")
	require.ErrorIs(t, err, apperr.ErrOtpInvalid)

	account, err := h.accounts.GetByNormalizedEmail(ctx, "owner@example.com")
	require.NoError(t, err)
	require.Equal(t, vo.AccountPendingVerification, account.Status)
}

// An unknown address must not be distinguishable from a wrong code.
func TestVerifyEmailHidesUnknownAccounts(t *testing.T) {
	h := newHarness(t)

	err := h.registration.VerifyEmail(context.Background(), "nobody@example.com", "123456")
	require.ErrorIs(t, err, apperr.ErrOtpInvalid)
}

// The resend endpoint answers the same way for an unknown address.
func TestRequestVerificationSucceedsForAnUnknownAddress(t *testing.T) {
	h := newHarness(t)

	require.NoError(t, h.registration.RequestVerification(context.Background(), "nobody@example.com"))
	require.Empty(t, h.publisher.types())
}

// Past the per-account resend budget the endpoint still answers 202 and simply
// sends nothing: a 429 would exist only for an address with a pending account,
// which is exactly what the endpoint must not disclose.
func TestRequestVerificationStaysSilentAfterTheResendLimit(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	require.NoError(t, h.registration.Register(ctx, service.RegisterCommand{
		Email:           testEmail,
		Password:        testPassword,
		ConfirmPassword: testPassword,
	}))
	sentAtRegistration := len(h.publisher.types())

	// The policy allows three resends inside the window.
	for i := 0; i < 3; i++ {
		require.NoError(t, h.registration.RequestVerification(ctx, testEmail))
	}
	sentWithinBudget := len(h.publisher.types())
	require.Equal(t, sentAtRegistration+3, sentWithinBudget)

	require.NoError(t, h.registration.RequestVerification(ctx, testEmail),
		"the answer must not differ from an unknown address")
	require.Equal(t, sentWithinBudget, len(h.publisher.types()), "nothing is sent past the budget")
}

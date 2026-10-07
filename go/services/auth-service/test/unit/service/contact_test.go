package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// The contact of a verified account is its address as the owner supplied it,
// flagged verified, with no locale in phase 1.
func TestResolveContactOfAVerifiedAccount(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)

	contact, err := h.contacts.Resolve(context.Background(), account.ID)
	require.NoError(t, err)
	require.Equal(t, account.ID, contact.UserID)
	require.Equal(t, testEmail, contact.Email)
	require.True(t, contact.EmailVerified)
	require.Nil(t, contact.Locale, "Auth stores no locale in phase 1")
}

// A pending account is still a recipient: transactional notices about its own
// activity may reach it, but the flag lets Notification withhold bulk mail.
func TestResolveContactOfAPendingAccountIsUnverified(t *testing.T) {
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

	contact, err := h.contacts.Resolve(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, testEmail, contact.Email)
	require.False(t, contact.EmailVerified)
}

// A lock does not change who owns the mailbox.
func TestResolveContactOfALockedAccountStaysVerified(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	for i := 0; i < 2; i++ {
		_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
		require.ErrorIs(t, err, apperr.ErrInvalidCredentials)
	}
	_, err := h.auth.Login(ctx, service.LoginCommand{Email: testEmail, Password: "wrong password here"})
	require.ErrorIs(t, err, apperr.ErrAccountLocked)

	locked, err := h.accounts.GetByID(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, vo.AccountLocked, locked.Status)

	contact, err := h.contacts.Resolve(ctx, account.ID)
	require.NoError(t, err)
	require.Equal(t, testEmail, contact.Email)
	require.True(t, contact.EmailVerified)
}

// AUTH-BR-007 anonymizes the address at deletion time, so a deleted account has
// no recipient and is reported exactly like an unknown one.
func TestResolveContactOfADeletedAccountIsNotFound(t *testing.T) {
	h := newHarness(t)
	account := h.registerAndVerify(t)
	ctx := context.Background()

	_, err := h.deletions.DeleteOwn(ctx, account.ID, testPassword)
	require.NoError(t, err)

	contact, err := h.contacts.Resolve(ctx, account.ID)
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
	require.Nil(t, contact)
}

func TestResolveContactOfAnUnknownAccountIsNotFound(t *testing.T) {
	h := newHarness(t)

	contact, err := h.contacts.Resolve(context.Background(), uuid.New())
	require.ErrorIs(t, err, apperr.ErrAccountNotFound)
	require.Nil(t, contact)
}

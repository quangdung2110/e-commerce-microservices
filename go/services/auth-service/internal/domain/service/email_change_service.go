package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// EmailChangeService implements WF-AUTH-007: the owner proves knowledge of the
// current password and control of the new mailbox before the identity moves.
type EmailChangeService struct {
	accounts port.AccountRepository
	otps     port.OtpStore
	hasher   port.PasswordHasher
	revoker  *Revoker
	notifier *Notifier
	clock    clock.Clock
	otp      OtpPolicy
	logger   *zap.Logger
}

// NewEmailChangeService wires the email-change use cases.
func NewEmailChangeService(
	accounts port.AccountRepository,
	otps port.OtpStore,
	hasher port.PasswordHasher,
	revoker *Revoker,
	notifier *Notifier,
	clk clock.Clock,
	otpPolicy OtpPolicy,
	logger *zap.Logger,
) *EmailChangeService {
	return &EmailChangeService{
		accounts: accounts,
		otps:     otps,
		hasher:   hasher,
		revoker:  revoker,
		notifier: notifier,
		clock:    clk,
		otp:      otpPolicy,
		logger:   logger,
	}
}

// RequestEmailChangeCommand is the input of an email-change request.
type RequestEmailChangeCommand struct {
	AccountID       uuid.UUID
	CurrentPassword string
	NewEmail        string
}

// Request verifies the current password and sends a code to the new address.
// Nothing is persisted until the code is confirmed.
func (s *EmailChangeService) Request(ctx context.Context, cmd RequestEmailChangeCommand) error {
	newEmail, err := vo.NewEmail(cmd.NewEmail)
	if err != nil {
		return err
	}

	account, err := s.accounts.GetByID(ctx, cmd.AccountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return apperr.ErrUnauthorized
		}
		return err
	}
	if !account.IsActive() {
		return apperr.ErrUnauthorized
	}

	valid, err := s.hasher.Verify(account.PasswordHash, cmd.CurrentPassword)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}
	if !valid {
		return apperr.ErrInvalidCredentials
	}

	// AUTH-BR-008: the new address must be free system-wide, including the
	// caller's own current address.
	if newEmail.Equal(account.Email) {
		return apperr.ErrEmailAlreadyRegistered
	}
	taken, err := s.accounts.ExistsByNormalizedEmail(ctx, newEmail.Normalized())
	if err != nil {
		return err
	}
	if taken {
		return apperr.ErrEmailAlreadyRegistered
	}

	count, err := s.otps.RegisterResend(ctx, constants.OtpPurposeEmailChange, account.ID, s.otp.ResendWindow)
	if err != nil {
		return err
	}
	if s.otp.ResendLimit > 0 && count > s.otp.ResendLimit {
		return apperr.ErrRateLimited.WithRetryAfter(s.otp.ResendWindow)
	}

	code, err := secret.NewNumericOTP(constants.OtpLength)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}
	expiresAt := s.clock.Now().Add(s.otp.TTL)

	challenge := port.OtpChallenge{
		AccountID: account.ID,
		Purpose:   constants.OtpPurposeEmailChange,
		CodeHash:  secret.HashHex(code),
		// Binding the challenge to the new address stops a code issued for one
		// address from confirming another.
		Subject:     newEmail.Normalized(),
		ExpiresAt:   expiresAt,
		MaxAttempts: s.otp.MaxAttempts,
	}
	if err := s.otps.Issue(ctx, challenge, s.otp.TTL); err != nil {
		return err
	}

	// The code goes to the address being claimed, never to the current one.
	s.notifier.OtpRequested(ctx, account.ID, newEmail.String(),
		constants.OtpPurposeEmailChange, code, expiresAt)
	return nil
}

// ConfirmEmailChangeCommand is the input of an email-change confirmation.
type ConfirmEmailChangeCommand struct {
	AccountID uuid.UUID
	NewEmail  string
	OTP       string
}

// Confirm consumes the code once, moves the identity and revokes every session
// so the client must sign in again with the new address.
func (s *EmailChangeService) Confirm(ctx context.Context, cmd ConfirmEmailChangeCommand) error {
	newEmail, err := vo.NewEmail(cmd.NewEmail)
	if err != nil {
		return err
	}
	if err := validateOtpFormat(cmd.OTP); err != nil {
		return err
	}

	account, err := s.accounts.GetByID(ctx, cmd.AccountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return apperr.ErrUnauthorized
		}
		return err
	}
	if !account.IsActive() {
		return apperr.ErrAccountNotActive
	}

	if err := s.otps.Consume(ctx, constants.OtpPurposeEmailChange, account.ID,
		secret.HashHex(cmd.OTP), newEmail.Normalized()); err != nil {
		return err
	}

	previousRecipient := recipientOf(account)
	now := s.clock.Now()
	if err := account.ChangeEmail(newEmail, now); err != nil {
		return err
	}
	if err := s.accounts.Update(ctx, account); err != nil {
		// The address may have been claimed between the pre-check and here; the
		// repository reports that as a conflict.
		return err
	}

	if _, err := s.revoker.RevokeAllSessions(ctx, account.ID); err != nil {
		return err
	}

	// The warning goes to the previous address, which is the one that would
	// notice an unauthorized change.
	s.notifier.EmailChanged(ctx, account.ID, previousRecipient, now)
	return nil
}

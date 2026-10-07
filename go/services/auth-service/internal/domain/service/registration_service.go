package service

import (
	"context"
	"errors"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/reqctx"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// OtpPolicy is the configured lifetime and abuse budget of a verification code.
type OtpPolicy struct {
	TTL          time.Duration
	MaxAttempts  int
	ResendLimit  int
	ResendWindow time.Duration
}

// RegistrationService implements WF-AUTH-001: create a PENDING_VERIFICATION
// account, deliver a code, and activate the account once the code is consumed.
type RegistrationService struct {
	accounts port.AccountRepository
	otps     port.OtpStore
	hasher   port.PasswordHasher
	notifier *Notifier
	clock    clock.Clock
	policy   vo.PasswordPolicy
	otp      OtpPolicy
	logger   *zap.Logger
}

// NewRegistrationService wires the registration and verification use cases.
func NewRegistrationService(
	accounts port.AccountRepository,
	otps port.OtpStore,
	hasher port.PasswordHasher,
	notifier *Notifier,
	clk clock.Clock,
	policy vo.PasswordPolicy,
	otpPolicy OtpPolicy,
	logger *zap.Logger,
) *RegistrationService {
	return &RegistrationService{
		accounts: accounts,
		otps:     otps,
		hasher:   hasher,
		notifier: notifier,
		clock:    clk,
		policy:   policy,
		otp:      otpPolicy,
		logger:   logger,
	}
}

// RegisterCommand is the validated input of a registration request.
type RegisterCommand struct {
	Email           string
	Password        string
	ConfirmPassword string
}

// Register creates the account and requests the verification email.
func (s *RegistrationService) Register(ctx context.Context, cmd RegisterCommand) error {
	email, err := vo.NewEmail(cmd.Email)
	if err != nil {
		return err
	}
	if err := validatePasswordFormat(s.policy, "password", cmd.Password, cmd.ConfirmPassword); err != nil {
		return err
	}
	// The registration contract has no 422, so a policy violation is reported as
	// a request-validation error.
	if err := asValidation(validatePasswordStrength(s.policy, cmd.Password, email), "password"); err != nil {
		return err
	}

	exists, err := s.accounts.ExistsByNormalizedEmail(ctx, email.Normalized())
	if err != nil {
		return err
	}
	if exists {
		return apperr.ErrEmailAlreadyRegistered
	}

	hash, err := s.hasher.Hash(cmd.Password)
	if err != nil {
		return err
	}

	account := entity.NewAccount(email, hash, s.clock.Now())
	if err := s.accounts.Create(ctx, account); err != nil {
		return err
	}

	return s.issueVerificationCode(ctx, account)
}

// RequestVerification resends the registration code. It always reports success
// so the endpoint cannot be used to discover which addresses have an account.
func (s *RegistrationService) RequestVerification(ctx context.Context, rawEmail string) error {
	email, err := vo.NewEmail(rawEmail)
	if err != nil {
		return err
	}

	account, err := s.accounts.GetByNormalizedEmail(ctx, email.Normalized())
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return nil
		}
		return err
	}
	if account.Status != vo.AccountPendingVerification {
		return nil
	}

	count, err := s.otps.RegisterResend(ctx, constants.OtpPurposeRegistration, account.ID, s.otp.ResendWindow)
	if err != nil {
		return err
	}
	if s.otp.ResendLimit > 0 && count > s.otp.ResendLimit {
		return apperr.ErrRateLimited.WithRetryAfter(s.otp.ResendWindow)
	}

	return s.issueVerificationCode(ctx, account)
}

// VerifyEmail consumes the code and activates the account. A replayed or
// expired code is rejected without changing any state (AUTH-BR-005).
func (s *RegistrationService) VerifyEmail(ctx context.Context, rawEmail, code string) error {
	email, err := vo.NewEmail(rawEmail)
	if err != nil {
		return err
	}
	if err := validateOtpFormat(code); err != nil {
		return err
	}

	account, err := s.accounts.GetByNormalizedEmail(ctx, email.Normalized())
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			// Do not disclose whether the address has an account.
			return apperr.ErrOtpInvalid
		}
		return err
	}
	if account.Status != vo.AccountPendingVerification {
		return apperr.ErrAccountNotActive.WithDetail("The account is not awaiting verification.")
	}

	if err := s.otps.Consume(ctx, constants.OtpPurposeRegistration, account.ID,
		secret.HashHex(code), email.Normalized()); err != nil {
		return err
	}

	now := s.clock.Now()
	if err := account.Activate(now); err != nil {
		return err
	}
	if err := s.accounts.Update(ctx, account); err != nil {
		return err
	}

	s.notifier.Publish(ctx, event.NewUserVerifiedSuccess(
		account.ID, vo.RoleStrings(account.Roles), now, reqctx.EventMetadata(ctx)))
	return nil
}

func (s *RegistrationService) issueVerificationCode(ctx context.Context, account *entity.Account) error {
	code, err := secret.NewNumericOTP(constants.OtpLength)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}
	expiresAt := s.clock.Now().Add(s.otp.TTL)

	challenge := port.OtpChallenge{
		AccountID:   account.ID,
		Purpose:     constants.OtpPurposeRegistration,
		CodeHash:    secret.HashHex(code),
		Subject:     account.Email.Normalized(),
		ExpiresAt:   expiresAt,
		MaxAttempts: s.otp.MaxAttempts,
	}
	if err := s.otps.Issue(ctx, challenge, s.otp.TTL); err != nil {
		return err
	}

	s.notifier.OtpRequested(ctx, account.ID, recipientOf(account),
		constants.OtpPurposeRegistration, code, expiresAt)
	return nil
}

// validateOtpFormat enforces the six-digit shape of the OpenAPI Otp schema.
func validateOtpFormat(code string) error {
	if len(code) != constants.OtpLength {
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "otp",
			Message: "must be six digits",
		})
	}
	for _, r := range code {
		if r < '0' || r > '9' {
			return apperr.ErrValidation.WithFields(apperr.FieldError{
				Field:   "otp",
				Message: "must be six digits",
			})
		}
	}
	return nil
}

package service

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// ResetPolicy is the configured lifetime and link format of a reset token.
type ResetPolicy struct {
	TTL time.Duration
	// URLTemplate carries the token in the fragment so it never lands in a
	// server log: https://app.example.com/reset-password#token={token}.
	URLTemplate string
}

// MinResetTokenLength matches the `reset_token` minLength of the contract.
const MinResetTokenLength = 43

// MaxResetTokenLength matches the `reset_token` maxLength of the contract.
const MaxResetTokenLength = 128

// PasswordService implements WF-AUTH-005: change a password with the current
// credential, or reset it with a single-use opaque token.
type PasswordService struct {
	accounts    port.AccountRepository
	resets      port.ResetTokenStore
	hasher      port.PasswordHasher
	limiter     port.RateLimiter
	revoker     *Revoker
	notifier    *Notifier
	clock       clock.Clock
	policy      vo.PasswordPolicy
	reset       ResetPolicy
	accountRule port.RateLimitRule
	logger      *zap.Logger
}

// NewPasswordService wires the password use cases.
func NewPasswordService(
	accounts port.AccountRepository,
	resets port.ResetTokenStore,
	hasher port.PasswordHasher,
	limiter port.RateLimiter,
	revoker *Revoker,
	notifier *Notifier,
	clk clock.Clock,
	policy vo.PasswordPolicy,
	reset ResetPolicy,
	accountRule port.RateLimitRule,
	logger *zap.Logger,
) *PasswordService {
	return &PasswordService{
		accounts:    accounts,
		resets:      resets,
		hasher:      hasher,
		limiter:     limiter,
		revoker:     revoker,
		notifier:    notifier,
		clock:       clk,
		policy:      policy,
		reset:       reset,
		accountRule: accountRule,
		logger:      logger,
	}
}

// ChangePasswordCommand is the input of an authenticated password change.
type ChangePasswordCommand struct {
	AccountID       uuid.UUID
	CurrentPassword string
	NewPassword     string
	ConfirmPassword string
}

// ChangePassword updates the credential and revokes every session (AUTH-BR-003).
func (s *PasswordService) ChangePassword(ctx context.Context, cmd ChangePasswordCommand) error {
	if err := validatePasswordFormat(s.policy, "new_password", cmd.NewPassword, cmd.ConfirmPassword); err != nil {
		return err
	}

	account, err := s.accounts.GetByID(ctx, cmd.AccountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return apperr.ErrUnauthorized
		}
		return err
	}
	// A pending or deleted account has no usable credential to replace.
	if account.Status != vo.AccountActive && account.Status != vo.AccountLocked {
		return apperr.ErrUnauthorized
	}

	valid, err := s.hasher.Verify(account.PasswordHash, cmd.CurrentPassword)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}
	if !valid {
		return apperr.ErrInvalidCredentials
	}
	if cmd.NewPassword == cmd.CurrentPassword {
		return apperr.ErrPasswordPolicy.WithDetail("The new password must differ from the current one.")
	}
	if err := validatePasswordStrength(s.policy, cmd.NewPassword, account.Email); err != nil {
		return err
	}

	// The current password was checked against this hash; if the credential moves
	// before the save, that check no longer holds.
	verifiedHash := account.PasswordHash
	return s.applyNewPassword(ctx, account, cmd.NewPassword, func(a *entity.Account) error {
		if a.PasswordHash != verifiedHash {
			return apperr.ErrInvalidCredentials
		}
		if a.Status != vo.AccountActive && a.Status != vo.AccountLocked {
			return apperr.ErrUnauthorized
		}
		return nil
	})
}

// RequestReset emails an opaque reset link. It always reports success so the
// endpoint cannot be used to discover which addresses have an account.
func (s *PasswordService) RequestReset(ctx context.Context, rawEmail string) error {
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
	// A pending or deleted account cannot be recovered through this flow.
	if account.Status != vo.AccountActive && account.Status != vo.AccountLocked {
		return nil
	}

	if s.accountRule.Enabled() {
		result, err := s.limiter.Allow(ctx, "password-reset:account", account.ID.String(), s.accountRule)
		if err != nil {
			return err
		}
		if !result.Allowed {
			// Anyone can send a request for any address, so a 429 that only an
			// existing account can trigger would disclose that the account
			// exists. The request is answered like any other and sends nothing;
			// the per-IP limit of the router is the throttle a client can observe.
			s.logger.Info("password reset suppressed by the per-account budget",
				zap.String("account_id", account.ID.String()))
			return nil
		}
	}

	token, err := secret.NewOpaqueToken(constants.ResetTokenBytes)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}
	expiresAt := s.clock.Now().Add(s.reset.TTL)
	if err := s.resets.Issue(ctx, secret.HashHex(token), account.ID, s.reset.TTL); err != nil {
		return err
	}

	s.notifier.PasswordResetRequested(ctx, account.ID, recipientOf(account), s.resetURL(token), expiresAt)
	return nil
}

// CompleteResetCommand is the input of a reset completion.
type CompleteResetCommand struct {
	ResetToken      string
	NewPassword     string
	ConfirmPassword string
}

// CompleteReset consumes the token exactly once, updates the credential and
// revokes every session.
func (s *PasswordService) CompleteReset(ctx context.Context, cmd CompleteResetCommand) error {
	if len(cmd.ResetToken) < MinResetTokenLength || len(cmd.ResetToken) > MaxResetTokenLength {
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "reset_token",
			Message: "is not a reset token issued by this API",
		})
	}
	if err := validatePasswordFormat(s.policy, "new_password", cmd.NewPassword, cmd.ConfirmPassword); err != nil {
		return err
	}

	accountID, err := s.resets.Consume(ctx, secret.HashHex(cmd.ResetToken))
	if err != nil {
		return err
	}

	account, err := s.accounts.GetByID(ctx, accountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return apperr.ErrResetTokenInvalid
		}
		return err
	}
	if account.Status != vo.AccountActive && account.Status != vo.AccountLocked {
		return apperr.ErrResetTokenInvalid
	}
	// The reset contract defines no 422 for policy, so a weak password is a
	// request-validation error here.
	if err := asValidation(validatePasswordStrength(s.policy, cmd.NewPassword, account.Email), "new_password"); err != nil {
		return err
	}

	return s.applyNewPassword(ctx, account, cmd.NewPassword, func(a *entity.Account) error {
		if a.Status != vo.AccountActive && a.Status != vo.AccountLocked {
			return apperr.ErrResetTokenInvalid
		}
		return nil
	})
}

// applyNewPassword performs the shared tail of both flows: persist the new
// credential, revoke every session and notify the owner. guard re-checks, on the
// row that is actually saved, what the caller verified on the row it first read;
// it runs again on every reload after a lost compare-and-set.
func (s *PasswordService) applyNewPassword(ctx context.Context, account *entity.Account, newPassword string, guard func(*entity.Account) error) error {
	hash, err := s.hasher.Hash(newPassword)
	if err != nil {
		return err
	}

	now := s.clock.Now()
	var recipient string
	account, _, err = updateAccount(ctx, s.accounts, account, func(a *entity.Account) (bool, error) {
		if err := guard(a); err != nil {
			return false, err
		}
		recipient = recipientOf(a)
		lockedBefore := a.Status == vo.AccountLocked
		if err := a.SetPasswordHash(hash, now); err != nil {
			return false, err
		}
		if lockedBefore {
			// Completing a reset or a change proves control of the account, so the
			// failed-login lock is lifted with it.
			a.ClearLockout(now)
		}
		return true, nil
	})
	if err != nil {
		return err
	}

	// The new credential is committed and neither a reset token nor the old
	// password can repeat this request, so everything that must follow a committed
	// change runs even when the revocation fails: the pending reset token is
	// invalidated and the owner is told (ADR-006). The revocation failure is
	// still reported to the caller at the end.
	_, revokeErr := s.revoker.RevokeAllSessions(ctx, account.ID)

	if err := s.resets.InvalidateForAccount(ctx, account.ID); err != nil {
		// A stale reset token is a security risk but the password already
		// changed; report it rather than failing the request.
		s.logger.Error("could not invalidate the pending reset token",
			zap.String("account_id", account.ID.String()),
			zap.Error(err),
		)
	}

	s.notifier.PasswordChanged(ctx, account.ID, recipient, now)
	return revokeErr
}

func (s *PasswordService) resetURL(token string) string {
	if strings.Contains(s.reset.URLTemplate, "{token}") {
		return strings.ReplaceAll(s.reset.URLTemplate, "{token}", token)
	}
	return s.reset.URLTemplate + token
}

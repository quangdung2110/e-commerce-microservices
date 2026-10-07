// Package entity holds the Auth aggregates. Account is the aggregate root for
// identity, credential, role and account state; sessions and signing keys have
// their own lifecycle.
package entity

import (
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// LockoutPolicy is the failed-login configuration applied to an account.
type LockoutPolicy struct {
	MaxFailedAttempts int
	LockDuration      time.Duration
	// CounterResetAfter drops a stale streak so unrelated mistakes do not add up.
	CounterResetAfter time.Duration
}

// Account is the identity used for authentication. It never holds profile data.
type Account struct {
	ID                uuid.UUID
	Email             vo.Email
	PasswordHash      string
	Status            vo.AccountStatus
	Roles             []vo.Role
	FailedLoginCount  int
	LastFailedLoginAt *time.Time
	LockedUntil       *time.Time
	PasswordChangedAt time.Time
	DeletedAt         *time.Time
	HardDeleteAfter   *time.Time
	DeletionReason    *vo.DeletionReason
	CreatedAt         time.Time
	UpdatedAt         time.Time
}

// NewAccount creates a PENDING_VERIFICATION account holding the USER role.
func NewAccount(email vo.Email, passwordHash string, now time.Time) *Account {
	now = now.UTC()
	return &Account{
		ID:                uuid.New(),
		Email:             email,
		PasswordHash:      passwordHash,
		Status:            vo.AccountPendingVerification,
		Roles:             []vo.Role{vo.RoleUser},
		PasswordChangedAt: now,
		CreatedAt:         now,
		UpdatedAt:         now,
	}
}

// IsActive reports whether the account may hold credentials (AUTH-INV-001).
func (a *Account) IsActive() bool { return a.Status == vo.AccountActive }

// IsDeleted reports whether the account reached its terminal state.
func (a *Account) IsDeleted() bool { return a.Status == vo.AccountDeleted }

// IsEmailVerified reports whether the owner proved control of the mailbox.
// Verification is what moves an account out of PENDING_VERIFICATION, so every
// later state (ACTIVE, LOCKED) implies a verified address; a DELETED account
// has no address at all.
func (a *Account) IsEmailVerified() bool {
	return a.Status != vo.AccountPendingVerification && !a.IsDeleted() && !a.Email.IsZero()
}

// HasRole reports whether the account holds the role.
func (a *Account) HasRole(role vo.Role) bool { return vo.HasRole(a.Roles, role) }

// LockRemaining returns how long the current lock still lasts.
func (a *Account) LockRemaining(now time.Time) time.Duration {
	if a.LockedUntil == nil {
		return 0
	}
	remaining := a.LockedUntil.Sub(now.UTC())
	if remaining < 0 {
		return 0
	}
	return remaining
}

// UnlockIfExpired returns the account to ACTIVE once the lock window has passed.
// It reports whether the state changed, so the caller knows to persist and to
// emit USER_UNLOCKED.
func (a *Account) UnlockIfExpired(now time.Time) bool {
	if a.Status != vo.AccountLocked || a.LockedUntil == nil {
		return false
	}
	if a.LockedUntil.After(now.UTC()) {
		return false
	}
	a.Status = vo.AccountActive
	a.LockedUntil = nil
	a.FailedLoginCount = 0
	a.LastFailedLoginAt = nil
	a.touch(now)
	return true
}

// EnsureCanAuthenticate enforces AUTH-BR-001. A deleted or unknown account is
// reported as invalid credentials so the endpoint does not enumerate accounts.
func (a *Account) EnsureCanAuthenticate(now time.Time) error {
	switch a.Status {
	case vo.AccountActive:
		return nil
	case vo.AccountPendingVerification:
		return apperr.ErrAccountNotVerified
	case vo.AccountLocked:
		remaining := a.LockRemaining(now)
		if remaining <= 0 {
			// The lock has expired; the caller unlocks before retrying.
			return nil
		}
		return apperr.ErrAccountLocked.WithRetryAfter(remaining)
	default:
		return apperr.ErrInvalidCredentials
	}
}

// RegisterFailedLogin applies AUTH-BR-004 and reports whether this attempt
// locked the account.
func (a *Account) RegisterFailedLogin(now time.Time, policy LockoutPolicy) bool {
	now = now.UTC()
	if policy.CounterResetAfter > 0 && a.LastFailedLoginAt != nil &&
		now.Sub(*a.LastFailedLoginAt) > policy.CounterResetAfter {
		a.FailedLoginCount = 0
	}

	a.FailedLoginCount++
	a.LastFailedLoginAt = &now
	a.touch(now)

	if policy.MaxFailedAttempts <= 0 || a.FailedLoginCount < policy.MaxFailedAttempts {
		return false
	}
	if a.Status != vo.AccountActive {
		// Only an active account transitions to LOCKED; other states already
		// refuse authentication.
		return false
	}
	lockedUntil := now.Add(policy.LockDuration)
	a.Status = vo.AccountLocked
	a.LockedUntil = &lockedUntil
	return true
}

// ResetFailedLogins clears the durable counter after a successful login.
func (a *Account) ResetFailedLogins(now time.Time) {
	if a.FailedLoginCount == 0 && a.LastFailedLoginAt == nil {
		return
	}
	a.FailedLoginCount = 0
	a.LastFailedLoginAt = nil
	a.touch(now)
}

// ClearLockout returns a locked account to ACTIVE because the owner proved
// control of the mailbox, for example by completing a password reset.
func (a *Account) ClearLockout(now time.Time) {
	if a.Status == vo.AccountLocked {
		a.Status = vo.AccountActive
	}
	a.LockedUntil = nil
	a.FailedLoginCount = 0
	a.LastFailedLoginAt = nil
	a.touch(now)
}

// Activate completes email verification. Any other source state is a conflict,
// which keeps a duplicate verification from emitting a second event.
func (a *Account) Activate(now time.Time) error {
	if a.Status != vo.AccountPendingVerification {
		return apperr.ErrAccountNotActive.WithDetail("The account is not awaiting verification.")
	}
	a.Status = vo.AccountActive
	a.LockedUntil = nil
	a.FailedLoginCount = 0
	a.LastFailedLoginAt = nil
	a.touch(now)
	return nil
}

// SetPasswordHash records a new credential and the change timestamp.
func (a *Account) SetPasswordHash(hash string, now time.Time) error {
	if a.IsDeleted() {
		return apperr.ErrAccountDeleted
	}
	a.PasswordHash = hash
	a.PasswordChangedAt = now.UTC()
	a.touch(now)
	return nil
}

// ChangeEmail applies AUTH-BR-008 once the OTP proved ownership of the address.
func (a *Account) ChangeEmail(email vo.Email, now time.Time) error {
	if a.IsDeleted() {
		return apperr.ErrAccountDeleted
	}
	if a.Status != vo.AccountActive {
		return apperr.ErrAccountNotActive
	}
	a.Email = email
	a.touch(now)
	return nil
}

// MarkDeleted applies AUTH-BR-007: anonymize immediately and schedule the hard
// delete exactly 90 days later. DELETED is terminal, so a second request is a
// conflict rather than a new retention window.
func (a *Account) MarkDeleted(reason vo.DeletionReason, now time.Time) error {
	if a.IsDeleted() {
		return apperr.ErrAccountNotActive.WithDetail("The account has already been deleted.")
	}
	if !reason.Valid() {
		return apperr.ErrInternal.WithDetail("Unsupported deletion reason.")
	}
	deletedAt := now.UTC()
	hardDeleteAfter := deletedAt.AddDate(0, 0, constants.HardDeleteRetentionDays)

	a.Status = vo.AccountDeleted
	a.Email = vo.Email{}
	a.PasswordHash = ""
	a.LockedUntil = nil
	a.FailedLoginCount = 0
	a.LastFailedLoginAt = nil
	a.DeletedAt = &deletedAt
	a.HardDeleteAfter = &hardDeleteAfter
	a.DeletionReason = &reason
	a.touch(now)
	return nil
}

func (a *Account) touch(now time.Time) { a.UpdatedAt = now.UTC() }

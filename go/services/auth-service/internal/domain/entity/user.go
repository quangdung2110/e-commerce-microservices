package entity

import (
	"time"

	"github.com/google/uuid"
)

// UserStatus represents the status of a user account
type UserStatus string

const (
	UserStatusPendingVerification UserStatus = "PENDING_VERIFICATION"
	UserStatusActive            UserStatus = "ACTIVE"
	UserStatusLocked            UserStatus = "LOCKED"
	UserStatusBanned            UserStatus = "BANNED"
)

// User represents a user entity in the auth system
type User struct {
	ID                   uuid.UUID  `json:"id"`
	Email                string     `json:"email"`
	PasswordHash         string     `json:"-"`
	Roles                []string   `json:"roles"`
	Status               UserStatus `json:"status"`
	LockedAt             *time.Time `json:"locked_at,omitempty"`
	FailedLoginAttempts  int        `json:"failed_login_attempts"`
	EmailVerifiedAt      *time.Time `json:"email_verified_at,omitempty"`
	CreatedAt            time.Time  `json:"created_at"`
	UpdatedAt            time.Time  `json:"updated_at"`
}

// IsActive returns true if user account is active
func (u *User) IsActive() bool {
	return u.Status == UserStatusActive
}

// IsLocked returns true if user account is locked
func (u *User) IsLocked() bool {
	return u.Status == UserStatusLocked
}

// IsBanned returns true if user account is banned
func (u *User) IsBanned() bool {
	return u.Status == UserStatusBanned
}

// IsPendingVerification returns true if user account is pending email verification
func (u *User) IsPendingVerification() bool {
	return u.Status == UserStatusPendingVerification
}

// CanAutoUnlock returns true if user account can be automatically unlocked
// Accounts locked due to failed login attempts can be auto-unlocked after 30 minutes
// BANNED accounts cannot be auto-unlocked
func (u *User) CanAutoUnlock() bool {
	if u.Status != UserStatusLocked {
		return false
	}
	if u.LockedAt == nil {
		return false
	}
	return true
}

// Lock locks the user account
func (u *User) Lock(lockedAt time.Time) {
	u.Status = UserStatusLocked
	u.LockedAt = &lockedAt
	u.FailedLoginAttempts = 0
}

// Unlock unlocks the user account
func (u *User) Unlock() {
	u.Status = UserStatusActive
	u.LockedAt = nil
	u.FailedLoginAttempts = 0
}

// IncrementFailedLoginAttempts increments the failed login counter
func (u *User) IncrementFailedLoginAttempts() {
	u.FailedLoginAttempts++
}

// ResetFailedLoginAttempts resets the failed login counter
func (u *User) ResetFailedLoginAttempts() {
	u.FailedLoginAttempts = 0
}

// HasReachedLockThreshold checks if user has reached the lock threshold
func (u *User) HasReachedLockThreshold(threshold int) bool {
	return u.FailedLoginAttempts >= threshold
}

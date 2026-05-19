package entity

import (
	"time"

	"github.com/ecommerce/auth-service/internal/domain/vo"
)

type User struct {
	ID                  string
	Email               vo.Email
	PasswordHash        string
	Roles               []string
	Status              vo.UserStatus
	LockedAt            *time.Time
	FailedLoginAttempts int
	EmailVerifiedAt     *time.Time
	CreatedAt           time.Time
	UpdatedAt           time.Time
	Revision            int64
}

func NewUser(email vo.Email, passwordHash string) *User {
	return &User{
		Email:               email,
		PasswordHash:        passwordHash,
		Roles:               []string{"USER"},
		Status:              vo.StatusPendingVerification,
		LockedAt:            nil,
		FailedLoginAttempts: 0,
		EmailVerifiedAt:     nil,
		CreatedAt:           time.Now(),
		UpdatedAt:           time.Now(),
		Revision:            0,
	}
}

func (u *User) IsActive() bool {
	return u.Status == vo.StatusActive
}

func (u *User) IsLocked() bool {
	return u.Status == vo.StatusLocked
}

func (u *User) IsBanned() bool {
	return u.Status == vo.StatusBanned
}

func (u *User) IsPendingVerification() bool {
	return u.Status == vo.StatusPendingVerification
}

func (u *User) IsEmailVerified() bool {
	return u.EmailVerifiedAt != nil
}

func (u *User) CanLogin() bool {
	return u.IsActive() && u.IsEmailVerified()
}

func (u *User) CanBeUnlocked() bool {
	if u.LockedAt == nil {
		return false
	}
	return time.Since(*u.LockedAt) >= 30*time.Minute
}

func (u *User) Activate() {
	u.Status = vo.StatusActive
	u.EmailVerifiedAt = nil
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) SetActive() {
	u.Status = vo.StatusActive
	u.LockedAt = nil
	u.FailedLoginAttempts = 0
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) SetEmailVerified() {
	u.Status = vo.StatusActive
	now := time.Now()
	u.EmailVerifiedAt = &now
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Lock() {
	u.Status = vo.StatusLocked
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) SetLocked() {
	u.Status = vo.StatusLocked
	now := time.Now()
	u.LockedAt = &now
	u.FailedLoginAttempts = 0
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Unlock() {
	u.Status = vo.StatusActive
	u.LockedAt = nil
	u.FailedLoginAttempts = 0
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Ban() {
	u.Status = vo.StatusBanned
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) IncrementFailedAttempts() {
	u.FailedLoginAttempts++
	if u.FailedLoginAttempts >= 5 {
		u.SetLocked()
	}
}

func (u *User) ResetFailedAttempts() {
	u.FailedLoginAttempts = 0
}

func (u *User) AddRole(role string) {
	for _, r := range u.Roles {
		if r == role {
			return
		}
	}
	u.Roles = append(u.Roles, role)
}

func (u *User) RemoveRole(role string) {
	for i, r := range u.Roles {
		if r == role {
			u.Roles = append(u.Roles[:i], u.Roles[i+1:]...)
			return
		}
	}
}

func (u *User) HasRole(role string) bool {
	for _, r := range u.Roles {
		if r == role {
			return true
		}
	}
	return false
}

package entity

import (
	"time"

	"github.com/ecommerce/auth-service/internal/domain/vo"
)

type User struct {
	ID           string
	Email        vo.Email
	PasswordHash string
	Status       vo.UserStatus
	CreatedAt    time.Time
	UpdatedAt    time.Time
	Revision     int64
}

func NewUser(email vo.Email, passwordHash string) *User {
	return &User{
		Email:        email,
		PasswordHash: passwordHash,
		Status:       vo.StatusPendingVerification,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}
}

func (u *User) Activate() {
	u.Status = vo.StatusActive
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Lock() {
	u.Status = vo.StatusLocked
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Ban() {
	u.Status = vo.StatusBanned
	u.UpdatedAt = time.Now()
	u.Revision++
}

func (u *User) Unlock() {
	u.Status = vo.StatusActive
	u.UpdatedAt = time.Now()
	u.Revision++
}

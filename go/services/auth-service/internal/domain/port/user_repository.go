package port

import (
	"context"
	"time"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	Status       string
	CreatedAt    time.Time
	UpdatedAt    time.Time
	Revision     int64
}

type UserRepository interface {
	Create(ctx context.Context, user *User) error
	GetByID(ctx context.Context, id string) (*User, error)
	GetByEmail(ctx context.Context, email string) (*User, error)
	Update(ctx context.Context, user *User) error
	Delete(ctx context.Context, id string) error
}

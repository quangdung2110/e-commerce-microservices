package port

import (
	"context"
	"time"
)

type UserRepository interface {
	Create(ctx context.Context, user interface{}) error
	GetByID(ctx context.Context, id string) (interface{}, error)
	GetByEmail(ctx context.Context, email string) (interface{}, error)
	Update(ctx context.Context, user interface{}) error
	UpdateStatus(ctx context.Context, id string, status string) error
	LockAccount(ctx context.Context, id string) error
	UnlockAccount(ctx context.Context, id string) error
	BanAccount(ctx context.Context, id string) error
	GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]interface{}, error)
	Delete(ctx context.Context, id string) error
}

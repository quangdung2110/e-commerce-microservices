package port

import (
	"context"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

type UserRepository interface {
	Create(ctx context.Context, user *entity.User) error
	GetByID(ctx context.Context, id string) (*entity.User, error)
	GetByEmail(ctx context.Context, email string) (*entity.User, error)
	Update(ctx context.Context, user *entity.User) error
	UpdateStatus(ctx context.Context, id string, status string) error
	LockAccount(ctx context.Context, id string) error
	UnlockAccount(ctx context.Context, id string) error
	BanAccount(ctx context.Context, id string) error
	GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]*entity.User, error)
	Delete(ctx context.Context, id string) error
}

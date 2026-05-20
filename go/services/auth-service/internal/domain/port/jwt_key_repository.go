package port

import (
	"context"
	"errors"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

// Common errors
var ErrNotFound = errors.New("resource not found")

type JWTKeyRepository interface {
	Create(ctx context.Context, key *entity.JWTKey) error
	GetActive(ctx context.Context) (*entity.JWTKey, error)
	GetAllForSigning(ctx context.Context) ([]*entity.JWTKey, error)
	GetAllForVerification(ctx context.Context) ([]*entity.JWTKey, error)
	GetByKid(ctx context.Context, kid string) (*entity.JWTKey, error)
	UpdateStatus(ctx context.Context, kid string, status string) error
	GetPhaseOutKeys(ctx context.Context, olderThan time.Time) ([]*entity.JWTKey, error)
	Delete(ctx context.Context, kid string) error
}

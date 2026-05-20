package port

import (
	"context"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

type RefreshTokenRepository interface {
	Create(ctx context.Context, token *entity.RefreshToken) error
	GetByID(ctx context.Context, id string) (*entity.RefreshToken, error)
	GetByTokenHash(ctx context.Context, hash string) (*entity.RefreshToken, error)
	GetActiveByUserID(ctx context.Context, userID string) ([]*entity.RefreshToken, error)
	Revoke(ctx context.Context, id string) error
	RevokeByHash(ctx context.Context, hash string) error
	RevokeAllForUser(ctx context.Context, userID string) error
	RevokeFamily(ctx context.Context, familyID string) error
	UpdateReplacedBy(ctx context.Context, oldID, newID string) error
	DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error)
}

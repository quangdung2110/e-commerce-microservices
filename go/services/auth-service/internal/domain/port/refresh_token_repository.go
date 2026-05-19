package port

import (
	"context"
	"time"
)

type RefreshTokenRepository interface {
	Create(ctx context.Context, token interface{}) error
	GetByID(ctx context.Context, id string) (interface{}, error)
	GetByTokenHash(ctx context.Context, hash string) (interface{}, error)
	GetActiveByUserID(ctx context.Context, userID string) ([]interface{}, error)
	Revoke(ctx context.Context, id string) error
	RevokeByHash(ctx context.Context, hash string) error
	RevokeAllForUser(ctx context.Context, userID string) error
	RevokeFamily(ctx context.Context, familyID string) error
	UpdateReplacedBy(ctx context.Context, oldID, newID string) error
	DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error)
}

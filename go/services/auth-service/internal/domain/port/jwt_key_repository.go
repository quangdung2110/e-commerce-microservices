package port

import (
	"context"
	"time"
)

type JWTKeyRepository interface {
	Create(ctx context.Context, key interface{}) error
	GetActive(ctx context.Context) (interface{}, error)
	GetAllForSigning(ctx context.Context) ([]interface{}, error)
	GetAllForVerification(ctx context.Context) ([]interface{}, error)
	GetByKid(ctx context.Context, kid string) (interface{}, error)
	UpdateStatus(ctx context.Context, kid string, status string) error
	GetPhaseOutKeys(ctx context.Context, olderThan time.Time) ([]interface{}, error)
	Delete(ctx context.Context, kid string) error
}

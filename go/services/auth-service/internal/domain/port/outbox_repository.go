package port

import (
	"context"
	"time"
)

type OutboxRepository interface {
	Create(ctx context.Context, event interface{}) error
	GetPending(ctx context.Context, limit int) ([]interface{}, error)
	Delete(ctx context.Context, eventID string) error
	DeleteOld(ctx context.Context, olderThan time.Time) (int64, error)
}

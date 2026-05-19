package port

import (
	"context"
	"time"
)

type AuditLogFilters struct {
	ActorID       string
	ActorType     string
	EventType     string
	SecurityLevel string
	LogCategory   string
	From          time.Time
	To            time.Time
}

type AuditLogRepository interface {
	Create(ctx context.Context, log interface{}) error
	GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]interface{}, error)
	GetByEventType(ctx context.Context, eventType string, limit, offset int) ([]interface{}, error)
	GetByTimeRange(ctx context.Context, from, to time.Time, limit, offset int) ([]interface{}, error)
	GetLoginHistory(ctx context.Context, userID string, limit int) ([]interface{}, error)
	GetByFilters(ctx context.Context, filters AuditLogFilters, limit, offset int) ([]interface{}, int, error)
	DeleteOlderThan(ctx context.Context, olderThan time.Time) (int64, error)
}

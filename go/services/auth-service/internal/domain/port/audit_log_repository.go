package port

import (
	"context"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
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
	Create(ctx context.Context, log *entity.AuditLog) error
	GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error)
	GetByEventType(ctx context.Context, eventType string, limit, offset int) ([]*entity.AuditLog, error)
	GetByTimeRange(ctx context.Context, from, to time.Time, limit, offset int) ([]*entity.AuditLog, error)
	GetLoginHistory(ctx context.Context, userID string, limit int) ([]*entity.AuditLog, error)
	GetByFilters(ctx context.Context, filters AuditLogFilters, limit, offset int) ([]*entity.AuditLog, int, error)
	DeleteOlderThan(ctx context.Context, olderThan time.Time) (int64, error)
}

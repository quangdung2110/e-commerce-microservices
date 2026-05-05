package port

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"user-service/internal/domain/entity"
)

// OutboxRepository defines the interface for outbox event data access
type OutboxRepository interface {
	// Create creates a new outbox event
	Create(ctx context.Context, event *entity.OutboxEvent) error

	// CreateTx creates an outbox event within a transaction
	CreateTx(ctx context.Context, tx pgx.Tx, event *entity.OutboxEvent) error

	// GetPendingEvents retrieves pending events for processing
	GetPendingEvents(ctx context.Context, limit int) ([]*entity.OutboxEvent, error)

	// DeleteOldEvents deletes events older than the specified time
	DeleteOldEvents(ctx context.Context, olderThan time.Time) (int64, error)

	// GetByID retrieves an outbox event by its ID
	GetByID(ctx context.Context, eventID uuid.UUID) (*entity.OutboxEvent, error)
}

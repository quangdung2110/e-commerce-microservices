package port

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"user-service/internal/domain/entity"
)

// UserDeletionRepository defines the interface for user deletion data access
// This is the Event Store for GDPR 2-phase deletion workflow
type UserDeletionRepository interface {
	// Create creates a new user deletion record
	Create(ctx context.Context, deletion *entity.UserDeletion) error

	// CreateTx creates a new user deletion record within a transaction
	CreateTx(ctx context.Context, tx pgx.Tx, deletion *entity.UserDeletion) error

	// GetByUserID retrieves a user deletion record by user ID
	GetByUserID(ctx context.Context, userID uuid.UUID) (*entity.UserDeletion, error)

	// GetPendingHardDeletes retrieves deletion records where retention window has expired
	GetPendingHardDeletes(ctx context.Context, limit int) ([]*entity.UserDeletion, error)

	// Delete deletes a user deletion record
	Delete(ctx context.Context, id uuid.UUID) error

	// DeleteTx deletes a user deletion record within a transaction
	DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error

	// Exists checks if a deletion record exists for a user
	Exists(ctx context.Context, userID uuid.UUID) (bool, error)

	// Count returns total number of deletion records
	Count(ctx context.Context) (int64, error)

	// CountPending returns count of records ready for hard delete
	CountPending(ctx context.Context) (int64, error)
}

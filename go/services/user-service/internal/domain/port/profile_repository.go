package port

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"user-service/internal/domain/entity"
)

// ProfileRepository defines the interface for profile data access
type ProfileRepository interface {
	// Create creates a new profile (idempotent - uses ON CONFLICT DO NOTHING)
	Create(ctx context.Context, profile *entity.Profile) error

	// CreateTx creates a new profile within a transaction
	CreateTx(ctx context.Context, tx pgx.Tx, profile *entity.Profile) error

	// GetByID retrieves a profile by its ID
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Profile, error)

	// Update updates an existing profile
	Update(ctx context.Context, profile *entity.Profile) error

	// UpdateTx updates a profile within a transaction
	UpdateTx(ctx context.Context, tx pgx.Tx, profile *entity.Profile) error

	// Delete hard deletes a profile
	Delete(ctx context.Context, id uuid.UUID) error

	// DeleteTx hard deletes a profile within a transaction
	DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error

	// Exists checks if a profile exists
	Exists(ctx context.Context, id uuid.UUID) (bool, error)

	// ExistsByPhoneNumber checks if a phone number is already used by another user
	ExistsByPhoneNumber(ctx context.Context, phone string, excludeUserID *uuid.UUID) (bool, error)

	// Anonymize anonymizes a profile for GDPR compliance (clears PII but keeps record)
	Anonymize(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error
}

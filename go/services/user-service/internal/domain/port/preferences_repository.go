package port

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"user-service/internal/domain/entity"
)

// PreferencesRepository defines the interface for preferences data access
type PreferencesRepository interface {
	// Create creates new preferences with defaults (idempotent - uses ON CONFLICT DO NOTHING)
	Create(ctx context.Context, prefs *entity.Preferences) error

	// CreateTx creates preferences within a transaction
	CreateTx(ctx context.Context, tx pgx.Tx, prefs *entity.Preferences) error

	// GetByUserID retrieves preferences by user ID
	GetByUserID(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error)

	// Update updates existing preferences
	Update(ctx context.Context, prefs *entity.Preferences) error

	// UpdateTx updates preferences within a transaction
	UpdateTx(ctx context.Context, tx pgx.Tx, prefs *entity.Preferences) error

	// Delete deletes preferences for a user
	Delete(ctx context.Context, userID uuid.UUID) error

	// DeleteTx deletes preferences within a transaction
	DeleteTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error

	// Exists checks if preferences exist for a user
	Exists(ctx context.Context, userID uuid.UUID) (bool, error)
}

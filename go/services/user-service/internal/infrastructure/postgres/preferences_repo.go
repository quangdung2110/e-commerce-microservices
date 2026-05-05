package postgres

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"user-service/internal/domain/entity"
)

// PostgresPreferencesRepository implements port.PreferencesRepository
type PostgresPreferencesRepository struct {
	db *pgxpool.Pool
}

// NewPostgresPreferencesRepository creates a new PostgresPreferencesRepository
func NewPostgresPreferencesRepository(db *pgxpool.Pool) *PostgresPreferencesRepository {
	return &PostgresPreferencesRepository{db: db}
}

// Create creates new preferences with defaults (idempotent - uses ON CONFLICT DO NOTHING)
func (r *PostgresPreferencesRepository) Create(ctx context.Context, prefs *entity.Preferences) error {
	query := `
		INSERT INTO user_preferences (user_id, language, currency, email_enabled, in_app_enabled, marketing_enabled, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, NOW())
		ON CONFLICT (user_id) DO NOTHING
		RETURNING updated_at`

	err := r.db.QueryRow(ctx, query,
		prefs.UserID,
		prefs.Language,
		prefs.Currency,
		prefs.EmailEnabled,
		prefs.InAppEnabled,
		prefs.MarketingEnabled,
	).Scan(&prefs.UpdatedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create preferences: %w", err)
	}

	return nil
}

// CreateTx creates preferences within a transaction
func (r *PostgresPreferencesRepository) CreateTx(ctx context.Context, tx pgx.Tx, prefs *entity.Preferences) error {
	query := `
		INSERT INTO user_preferences (user_id, language, currency, email_enabled, in_app_enabled, marketing_enabled, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, NOW())
		ON CONFLICT (user_id) DO NOTHING
		RETURNING updated_at`

	err := tx.QueryRow(ctx, query,
		prefs.UserID,
		prefs.Language,
		prefs.Currency,
		prefs.EmailEnabled,
		prefs.InAppEnabled,
		prefs.MarketingEnabled,
	).Scan(&prefs.UpdatedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create preferences in transaction: %w", err)
	}

	return nil
}

// GetByUserID retrieves preferences by user ID
func (r *PostgresPreferencesRepository) GetByUserID(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error) {
	query := `
		SELECT user_id, language, currency, email_enabled, in_app_enabled, marketing_enabled, updated_at
		FROM user_preferences
		WHERE user_id = $1`

	var prefs entity.Preferences
	err := r.db.QueryRow(ctx, query, userID).Scan(
		&prefs.UserID,
		&prefs.Language,
		&prefs.Currency,
		&prefs.EmailEnabled,
		&prefs.InAppEnabled,
		&prefs.MarketingEnabled,
		&prefs.UpdatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get preferences: %w", err)
	}

	return &prefs, nil
}

// Update updates existing preferences
func (r *PostgresPreferencesRepository) Update(ctx context.Context, prefs *entity.Preferences) error {
	query := `
		UPDATE user_preferences
		SET language = $2,
		    currency = $3,
		    email_enabled = $4,
		    in_app_enabled = $5,
		    marketing_enabled = $6,
		    updated_at = NOW()
		WHERE user_id = $1
		RETURNING updated_at`

	err := r.db.QueryRow(ctx, query,
		prefs.UserID,
		prefs.Language,
		prefs.Currency,
		prefs.EmailEnabled,
		prefs.InAppEnabled,
		prefs.MarketingEnabled,
	).Scan(&prefs.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update preferences: %w", err)
	}

	return nil
}

// UpdateTx updates preferences within a transaction
func (r *PostgresPreferencesRepository) UpdateTx(ctx context.Context, tx pgx.Tx, prefs *entity.Preferences) error {
	query := `
		UPDATE user_preferences
		SET language = $2,
		    currency = $3,
		    email_enabled = $4,
		    in_app_enabled = $5,
		    marketing_enabled = $6,
		    updated_at = NOW()
		WHERE user_id = $1
		RETURNING updated_at`

	err := tx.QueryRow(ctx, query,
		prefs.UserID,
		prefs.Language,
		prefs.Currency,
		prefs.EmailEnabled,
		prefs.InAppEnabled,
		prefs.MarketingEnabled,
	).Scan(&prefs.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update preferences in transaction: %w", err)
	}

	return nil
}

// Delete deletes preferences for a user
func (r *PostgresPreferencesRepository) Delete(ctx context.Context, userID uuid.UUID) error {
	query := `DELETE FROM user_preferences WHERE user_id = $1`

	result, err := r.db.Exec(ctx, query, userID)
	if err != nil {
		return fmt.Errorf("failed to delete preferences: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// DeleteTx deletes preferences within a transaction
func (r *PostgresPreferencesRepository) DeleteTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	query := `DELETE FROM user_preferences WHERE user_id = $1`

	result, err := tx.Exec(ctx, query, userID)
	if err != nil {
		return fmt.Errorf("failed to delete preferences in transaction: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// Exists checks if preferences exist for a user
func (r *PostgresPreferencesRepository) Exists(ctx context.Context, userID uuid.UUID) (bool, error) {
	query := `SELECT EXISTS(SELECT 1 FROM user_preferences WHERE user_id = $1)`

	var exists bool
	err := r.db.QueryRow(ctx, query, userID).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("failed to check preferences existence: %w", err)
	}

	return exists, nil
}

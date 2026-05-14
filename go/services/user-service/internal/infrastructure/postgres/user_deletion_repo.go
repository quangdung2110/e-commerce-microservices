package postgres

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"user-service/internal/domain/entity"
)

// PostgresUserDeletionRepository implements port.UserDeletionRepository
type PostgresUserDeletionRepository struct {
	db *pgxpool.Pool
}

// NewPostgresUserDeletionRepository creates a new PostgresUserDeletionRepository
func NewPostgresUserDeletionRepository(db *pgxpool.Pool) *PostgresUserDeletionRepository {
	return &PostgresUserDeletionRepository{db: db}
}

// Create creates a new user deletion record
func (r *PostgresUserDeletionRepository) Create(ctx context.Context, deletion *entity.UserDeletion) error {
	query := `
		INSERT INTO user_deletions (id, user_id, deleted_at, retention_window_expires_at, reason)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (id) DO NOTHING
		RETURNING deleted_at`

	err := r.db.QueryRow(ctx, query,
		deletion.ID,
		deletion.UserID,
		deletion.DeletedAt,
		deletion.RetentionWindowExpiresAt,
		deletion.Reason,
	).Scan(&deletion.DeletedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create user deletion: %w", err)
	}

	return nil
}

// CreateTx creates a new user deletion record within a transaction
func (r *PostgresUserDeletionRepository) CreateTx(ctx context.Context, tx pgx.Tx, deletion *entity.UserDeletion) error {
	query := `
		INSERT INTO user_deletions (id, user_id, deleted_at, retention_window_expires_at, reason)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (id) DO NOTHING
		RETURNING deleted_at`

	err := tx.QueryRow(ctx, query,
		deletion.ID,
		deletion.UserID,
		deletion.DeletedAt,
		deletion.RetentionWindowExpiresAt,
		deletion.Reason,
	).Scan(&deletion.DeletedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create user deletion in transaction: %w", err)
	}

	return nil
}

// GetByUserID retrieves a user deletion record by user ID
func (r *PostgresUserDeletionRepository) GetByUserID(ctx context.Context, userID uuid.UUID) (*entity.UserDeletion, error) {
	query := `
		SELECT id, user_id, deleted_at, retention_window_expires_at, reason
		FROM user_deletions
		WHERE user_id = $1`

	var deletion entity.UserDeletion
	err := r.db.QueryRow(ctx, query, userID).Scan(
		&deletion.ID,
		&deletion.UserID,
		&deletion.DeletedAt,
		&deletion.RetentionWindowExpiresAt,
		&deletion.Reason,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get user deletion: %w", err)
	}

	return &deletion, nil
}

// GetPendingHardDeletes retrieves deletion records where retention window has expired
func (r *PostgresUserDeletionRepository) GetPendingHardDeletes(ctx context.Context, limit int) ([]*entity.UserDeletion, error) {
	query := `
		SELECT id, user_id, deleted_at, retention_window_expires_at, reason
		FROM user_deletions
		WHERE retention_window_expires_at <= NOW()
		ORDER BY retention_window_expires_at ASC
		LIMIT $1`

	rows, err := r.db.Query(ctx, query, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get pending hard deletes: %w", err)
	}
	defer rows.Close()

	var deletions []*entity.UserDeletion
	for rows.Next() {
		var deletion entity.UserDeletion
		err := rows.Scan(
			&deletion.ID,
			&deletion.UserID,
			&deletion.DeletedAt,
			&deletion.RetentionWindowExpiresAt,
			&deletion.Reason,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan user deletion: %w", err)
		}
		deletions = append(deletions, &deletion)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}

	return deletions, nil
}

// Delete deletes a user deletion record
func (r *PostgresUserDeletionRepository) Delete(ctx context.Context, id uuid.UUID) error {
	query := `DELETE FROM user_deletions WHERE id = $1`

	result, err := r.db.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete user deletion: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// DeleteTx deletes a user deletion record within a transaction
func (r *PostgresUserDeletionRepository) DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	query := `DELETE FROM user_deletions WHERE id = $1`

	result, err := tx.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete user deletion in transaction: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// Exists checks if a deletion record exists for a user
func (r *PostgresUserDeletionRepository) Exists(ctx context.Context, userID uuid.UUID) (bool, error) {
	query := `SELECT EXISTS(SELECT 1 FROM user_deletions WHERE user_id = $1)`

	var exists bool
	err := r.db.QueryRow(ctx, query, userID).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("failed to check user deletion existence: %w", err)
	}

	return exists, nil
}

// Count returns total number of user deletions (for metrics)
func (r *PostgresUserDeletionRepository) Count(ctx context.Context) (int64, error) {
	query := `SELECT COUNT(*) FROM user_deletions`

	var count int64
	err := r.db.QueryRow(ctx, query).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count user deletions: %w", err)
	}

	return count, nil
}

// CountPending returns count of records ready for hard delete
func (r *PostgresUserDeletionRepository) CountPending(ctx context.Context) (int64, error) {
	query := `SELECT COUNT(*) FROM user_deletions WHERE retention_window_expires_at <= NOW()`

	var count int64
	err := r.db.QueryRow(ctx, query).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count pending deletions: %w", err)
	}

	return count, nil
}

// Ping checks database connectivity
func (r *PostgresUserDeletionRepository) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return r.db.Ping(ctx)
}

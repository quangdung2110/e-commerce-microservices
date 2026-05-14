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
	"user-service/internal/domain/vo"
)

// PostgresProfileRepository implements port.ProfileRepository
type PostgresProfileRepository struct {
	db *pgxpool.Pool
}

// NewPostgresProfileRepository creates a new PostgresProfileRepository
func NewPostgresProfileRepository(db *pgxpool.Pool) *PostgresProfileRepository {
	return &PostgresProfileRepository{db: db}
}

// Create creates a new profile (idempotent - uses ON CONFLICT DO NOTHING)
func (r *PostgresProfileRepository) Create(ctx context.Context, profile *entity.Profile) error {
	query := `
		INSERT INTO user_profiles (id, first_name, last_name, phone_number, date_of_birth, gender, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, NOW(), NOW())
		ON CONFLICT (id) DO NOTHING
		RETURNING created_at, updated_at`

	err := r.db.QueryRow(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.PhoneNumber,
		profile.DateOfBirth,
		profile.Gender.String(),
	).Scan(&profile.CreatedAt, &profile.UpdatedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create profile: %w", err)
	}

	return nil
}

// CreateTx creates a new profile within a transaction
func (r *PostgresProfileRepository) CreateTx(ctx context.Context, tx pgx.Tx, profile *entity.Profile) error {
	query := `
		INSERT INTO user_profiles (id, first_name, last_name, phone_number, date_of_birth, gender, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, NOW(), NOW())
		ON CONFLICT (id) DO NOTHING
		RETURNING created_at, updated_at`

	err := tx.QueryRow(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.PhoneNumber,
		profile.DateOfBirth,
		profile.Gender.String(),
	).Scan(&profile.CreatedAt, &profile.UpdatedAt)

	if err != nil && err != pgx.ErrNoRows {
		return fmt.Errorf("failed to create profile in transaction: %w", err)
	}

	return nil
}

// GetByID retrieves a profile by its ID
func (r *PostgresProfileRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Profile, error) {
	query := `
		SELECT id, first_name, last_name, phone_number, date_of_birth, gender, created_at, updated_at
		FROM user_profiles
		WHERE id = $1`

	var profile entity.Profile
	var gender string

	err := r.db.QueryRow(ctx, query, id).Scan(
		&profile.ID,
		&profile.FirstName,
		&profile.LastName,
		&profile.PhoneNumber,
		&profile.DateOfBirth,
		&gender,
		&profile.CreatedAt,
		&profile.UpdatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get profile: %w", err)
	}

	profile.Gender = vo.Gender(gender)
	return &profile, nil
}

// Update updates an existing profile
func (r *PostgresProfileRepository) Update(ctx context.Context, profile *entity.Profile) error {
	query := `
		UPDATE user_profiles
		SET first_name = $2,
		    last_name = $3,
		    phone_number = $4,
		    date_of_birth = $5,
		    gender = $6,
		    updated_at = NOW()
		WHERE id = $1
		RETURNING updated_at`

	err := r.db.QueryRow(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.PhoneNumber,
		profile.DateOfBirth,
		profile.Gender.String(),
	).Scan(&profile.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update profile: %w", err)
	}

	return nil
}

// UpdateTx updates a profile within a transaction
func (r *PostgresProfileRepository) UpdateTx(ctx context.Context, tx pgx.Tx, profile *entity.Profile) error {
	query := `
		UPDATE user_profiles
		SET first_name = $2,
		    last_name = $3,
		    phone_number = $4,
		    date_of_birth = $5,
		    gender = $6,
		    updated_at = NOW()
		WHERE id = $1
		RETURNING updated_at`

	err := tx.QueryRow(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.PhoneNumber,
		profile.DateOfBirth,
		profile.Gender.String(),
	).Scan(&profile.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update profile in transaction: %w", err)
	}

	return nil
}

// Delete hard deletes a profile
func (r *PostgresProfileRepository) Delete(ctx context.Context, id uuid.UUID) error {
	query := `DELETE FROM user_profiles WHERE id = $1`

	result, err := r.db.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete profile: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// DeleteTx hard deletes a profile within a transaction
func (r *PostgresProfileRepository) DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	query := `DELETE FROM user_profiles WHERE id = $1`

	result, err := tx.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete profile in transaction: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// Exists checks if a profile exists
func (r *PostgresProfileRepository) Exists(ctx context.Context, id uuid.UUID) (bool, error) {
	query := `SELECT EXISTS(SELECT 1 FROM user_profiles WHERE id = $1)`

	var exists bool
	err := r.db.QueryRow(ctx, query, id).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("failed to check profile existence: %w", err)
	}

	return exists, nil
}

// ExistsByPhoneNumber checks if a phone number is already used by another user
func (r *PostgresProfileRepository) ExistsByPhoneNumber(ctx context.Context, phone string, excludeUserID *uuid.UUID) (bool, error) {
	var query string
	var args []interface{}

	if excludeUserID != nil {
		query = `SELECT EXISTS(SELECT 1 FROM user_profiles WHERE phone_number = $1 AND id != $2)`
		args = []interface{}{phone, *excludeUserID}
	} else {
		query = `SELECT EXISTS(SELECT 1 FROM user_profiles WHERE phone_number = $1)`
		args = []interface{}{phone}
	}

	var exists bool
	err := r.db.QueryRow(ctx, query, args...).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("failed to check phone number existence: %w", err)
	}

	return exists, nil
}

// GetByIDWithTx retrieves a profile by its ID within a transaction
func (r *PostgresProfileRepository) GetByIDWithTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) (*entity.Profile, error) {
	query := `
		SELECT id, first_name, last_name, phone_number, date_of_birth, gender, created_at, updated_at
		FROM user_profiles
		WHERE id = $1`

	var profile entity.Profile
	var gender string

	err := tx.QueryRow(ctx, query, id).Scan(
		&profile.ID,
		&profile.FirstName,
		&profile.LastName,
		&profile.PhoneNumber,
		&profile.DateOfBirth,
		&gender,
		&profile.CreatedAt,
		&profile.UpdatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get profile with tx: %w", err)
	}

	profile.Gender = vo.Gender(gender)
	return &profile, nil
}

// Count returns total number of profiles (for metrics)
func (r *PostgresProfileRepository) Count(ctx context.Context) (int64, error) {
	query := `SELECT COUNT(*) FROM user_profiles`

	var count int64
	err := r.db.QueryRow(ctx, query).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count profiles: %w", err)
	}

	return count, nil
}

// Ping checks database connectivity
func (r *PostgresProfileRepository) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return r.db.Ping(ctx)
}

// Anonymize anonymizes a profile for GDPR compliance (clears PII but keeps record)
func (r *PostgresProfileRepository) Anonymize(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	query := `
		UPDATE user_profiles
		SET first_name = 'Deleted',
		    last_name = 'User',
		    phone_number = NULL,
		    date_of_birth = NULL,
		    gender = 'UNKNOWN',
		    updated_at = NOW()
		WHERE id = $1`

	result, err := tx.Exec(ctx, query, userID)
	if err != nil {
		return fmt.Errorf("failed to anonymize profile: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

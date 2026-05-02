package pg_adapter

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// PostgresJwtKeyRepository implements repository.JwtKeyRepository using PostgreSQL
type PostgresJwtKeyRepository struct {
	pool *pgxpool.Pool
}

// NewPostgresJwtKeyRepository creates a new JwtKeyRepository
func NewPostgresJwtKeyRepository(pool *pgxpool.Pool) repository.JwtKeyRepository {
	return &PostgresJwtKeyRepository{pool: pool}
}

// Create creates a new JWT key
func (r *PostgresJwtKeyRepository) Create(ctx context.Context, key *entity.JwtKey) error {
	query := `
		INSERT INTO jwt_keys (
			kid, public_key, private_key_encrypted, status, algorithm,
			created_at, rotated_at, expires_at, version, key_size_bits, created_by
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	`

	_, err := r.pool.Exec(ctx, query,
		key.Kid,
		key.PublicKey,
		key.PrivateKeyEncrypted,
		key.Status,
		key.Algorithm,
		key.CreatedAt,
		key.RotatedAt,
		key.ExpiresAt,
		key.Version,
		key.KeySizeBits,
		key.CreatedBy,
	)
	return err
}

// GetByKid retrieves a key by its ID
func (r *PostgresJwtKeyRepository) GetByKid(ctx context.Context, kid string) (*entity.JwtKey, error) {
	query := `
		SELECT kid, public_key, private_key_encrypted, status, algorithm,
			   created_at, rotated_at, expires_at, version, key_size_bits, created_by
		FROM jwt_keys
		WHERE kid = $1
	`

	var key entity.JwtKey
	err := r.pool.QueryRow(ctx, query, kid).Scan(
		&key.Kid,
		&key.PublicKey,
		&key.PrivateKeyEncrypted,
		&key.Status,
		&key.Algorithm,
		&key.CreatedAt,
		&key.RotatedAt,
		&key.ExpiresAt,
		&key.Version,
		&key.KeySizeBits,
		&key.CreatedBy,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, repository.ErrKeyNotFound
		}
		return nil, err
	}
	return &key, nil
}

// GetActive retrieves the active key for signing
func (r *PostgresJwtKeyRepository) GetActive(ctx context.Context) (*entity.JwtKey, error) {
	query := `
		SELECT kid, public_key, private_key_encrypted, status, algorithm,
			   created_at, rotated_at, expires_at, version, key_size_bits, created_by
		FROM jwt_keys
		WHERE status = $1
		  AND (expires_at IS NULL OR expires_at > NOW())
		ORDER BY created_at DESC
		LIMIT 1
	`

	var key entity.JwtKey
	err := r.pool.QueryRow(ctx, query, types.KeyStatusActive).Scan(
		&key.Kid,
		&key.PublicKey,
		&key.PrivateKeyEncrypted,
		&key.Status,
		&key.Algorithm,
		&key.CreatedAt,
		&key.RotatedAt,
		&key.ExpiresAt,
		&key.Version,
		&key.KeySizeBits,
		&key.CreatedBy,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, repository.ErrKeyNotFound
		}
		return nil, err
	}
	return &key, nil
}

// GetAllActive retrieves all keys that can be used for verification (ACTIVE + PHASE_OUT)
func (r *PostgresJwtKeyRepository) GetAllActive(ctx context.Context) ([]*entity.JwtKey, error) {
	query := `
		SELECT kid, public_key, private_key_encrypted, status, algorithm,
			   created_at, rotated_at, expires_at, version, key_size_bits, created_by
		FROM jwt_keys
		WHERE status IN ($1, $2)
		  AND (expires_at IS NULL OR expires_at > NOW())
		ORDER BY created_at DESC
	`

	rows, err := r.pool.Query(ctx, query, types.KeyStatusActive, types.KeyStatusPhaseOut)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var keys []*entity.JwtKey
	for rows.Next() {
		var key entity.JwtKey
		err := rows.Scan(
			&key.Kid,
			&key.PublicKey,
			&key.PrivateKeyEncrypted,
			&key.Status,
			&key.Algorithm,
			&key.CreatedAt,
			&key.RotatedAt,
			&key.ExpiresAt,
			&key.Version,
			&key.KeySizeBits,
			&key.CreatedBy,
		)
		if err != nil {
			return nil, err
		}
		keys = append(keys, &key)
	}
	return keys, rows.Err()
}

// UpdateStatus updates the status of a key
func (r *PostgresJwtKeyRepository) UpdateStatus(ctx context.Context, kid string, status types.KeyStatus) error {
	query := `
		UPDATE jwt_keys
		SET status = $2,
			rotated_at = CASE WHEN $2 = 'PHASE_OUT' THEN NOW() ELSE rotated_at END,
			version = version + 1
		WHERE kid = $1
	`

	result, err := r.pool.Exec(ctx, query, kid, status)
	if err != nil {
		return err
	}
	if result.RowsAffected() == 0 {
		return pgx.ErrNoRows
	}
	return nil
}

// Delete deletes a key
func (r *PostgresJwtKeyRepository) Delete(ctx context.Context, kid string) error {
	_, err := r.pool.Exec(ctx, "DELETE FROM jwt_keys WHERE kid = $1", kid)
	return err
}

// DeleteExpired deletes expired keys
func (r *PostgresJwtKeyRepository) DeleteExpired(ctx context.Context) (int64, error) {
	result, err := r.pool.Exec(ctx, `
		DELETE FROM jwt_keys
		WHERE expires_at IS NOT NULL AND expires_at < NOW()
		  AND status = 'RETIRED'
	`)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

// GetByStatus retrieves keys by status
func (r *PostgresJwtKeyRepository) GetByStatus(ctx context.Context, status types.KeyStatus) ([]*entity.JwtKey, error) {
	query := `
		SELECT kid, public_key, private_key_encrypted, status, algorithm,
			   created_at, rotated_at, expires_at, version, key_size_bits, created_by
		FROM jwt_keys
		WHERE status = $1
		ORDER BY created_at DESC
	`

	rows, err := r.pool.Query(ctx, query, status)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var keys []*entity.JwtKey
	for rows.Next() {
		var key entity.JwtKey
		err := rows.Scan(
			&key.Kid,
			&key.PublicKey,
			&key.PrivateKeyEncrypted,
			&key.Status,
			&key.Algorithm,
			&key.CreatedAt,
			&key.RotatedAt,
			&key.ExpiresAt,
			&key.Version,
			&key.KeySizeBits,
			&key.CreatedBy,
		)
		if err != nil {
			return nil, err
		}
		keys = append(keys, &key)
	}
	return keys, rows.Err()
}

// Ensure entity package is imported
var _ = uuid.New
var _ = time.Now

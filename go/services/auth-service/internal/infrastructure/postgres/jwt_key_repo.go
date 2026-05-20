package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

type JWTKeyRepository struct {
	pool *pgxpool.Pool
}

func NewJWTKeyRepository(pool *pgxpool.Pool) *JWTKeyRepository {
	return &JWTKeyRepository{pool: pool}
}

func (r *JWTKeyRepository) Create(ctx context.Context, key *entity.JWTKey) error {
	sql := `
		INSERT INTO jwt_keys (kid, public_key, private_key, status, created_at, rotated_at, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`

	_, err := r.pool.Exec(ctx, sql,
		key.Kid,
		key.PublicKey,
		key.PrivateKey,
		key.Status,
		key.CreatedAt,
		key.RotatedAt,
		key.ExpiresAt,
	)
	if err != nil {
		return fmt.Errorf("failed to create JWT key: %w", err)
	}

	return nil
}

func (r *JWTKeyRepository) GetActive(ctx context.Context) (*entity.JWTKey, error) {
	sql := `
		SELECT kid, public_key, private_key, status, created_at, rotated_at, expires_at
		FROM jwt_keys
		WHERE status = 'ACTIVE'
		ORDER BY created_at DESC
		LIMIT 1
	`

	row := r.pool.QueryRow(ctx, sql)
	key, err := r.scanJWTKey(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get active JWT key: %w", err)
	}

	return key, nil
}

func (r *JWTKeyRepository) GetAllForSigning(ctx context.Context) ([]*entity.JWTKey, error) {
	sql := `
		SELECT kid, public_key, private_key, status, created_at, rotated_at, expires_at
		FROM jwt_keys
		WHERE status = 'ACTIVE'
		ORDER BY created_at DESC
	`

	rows, err := r.pool.Query(ctx, sql)
	if err != nil {
		return nil, fmt.Errorf("failed to get JWT keys for signing: %w", err)
	}
	defer rows.Close()

	return r.scanJWTKeys(rows)
}

func (r *JWTKeyRepository) GetAllForVerification(ctx context.Context) ([]*entity.JWTKey, error) {
	sql := `
		SELECT kid, public_key, private_key, status, created_at, rotated_at, expires_at
		FROM jwt_keys
		WHERE status IN ('ACTIVE', 'PHASE_OUT')
		ORDER BY created_at DESC
	`

	rows, err := r.pool.Query(ctx, sql)
	if err != nil {
		return nil, fmt.Errorf("failed to get JWT keys for verification: %w", err)
	}
	defer rows.Close()

	return r.scanJWTKeys(rows)
}

func (r *JWTKeyRepository) GetByKid(ctx context.Context, kid string) (*entity.JWTKey, error) {
	sql := `
		SELECT kid, public_key, private_key, status, created_at, rotated_at, expires_at
		FROM jwt_keys
		WHERE kid = $1
	`

	row := r.pool.QueryRow(ctx, sql, kid)
	key, err := r.scanJWTKey(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get JWT key by kid: %w", err)
	}

	return key, nil
}

func (r *JWTKeyRepository) UpdateStatus(ctx context.Context, kid string, status string) error {
	sql := `UPDATE jwt_keys SET status = $2, rotated_at = NOW() WHERE kid = $1`

	result, err := r.pool.Exec(ctx, sql, kid, status)
	if err != nil {
		return fmt.Errorf("failed to update JWT key status: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *JWTKeyRepository) GetPhaseOutKeys(ctx context.Context, olderThan time.Time) ([]*entity.JWTKey, error) {
	sql := `
		SELECT kid, public_key, private_key, status, created_at, rotated_at, expires_at
		FROM jwt_keys
		WHERE status = 'PHASE_OUT' AND rotated_at < $1
		ORDER BY rotated_at ASC
	`

	rows, err := r.pool.Query(ctx, sql, olderThan)
	if err != nil {
		return nil, fmt.Errorf("failed to get phase out keys: %w", err)
	}
	defer rows.Close()

	return r.scanJWTKeys(rows)
}

func (r *JWTKeyRepository) Delete(ctx context.Context, kid string) error {
	sql := `DELETE FROM jwt_keys WHERE kid = $1`

	result, err := r.pool.Exec(ctx, sql, kid)
	if err != nil {
		return fmt.Errorf("failed to delete JWT key: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *JWTKeyRepository) scanJWTKey(row interface {
	Scan(dest ...interface{}) error
}) (*entity.JWTKey, error) {
	var key entity.JWTKey
	err := row.Scan(
		&key.Kid,
		&key.PublicKey,
		&key.PrivateKey,
		&key.Status,
		&key.CreatedAt,
		&key.RotatedAt,
		&key.ExpiresAt,
	)
	if err != nil {
		return nil, err
	}
	return &key, nil
}

func (r *JWTKeyRepository) scanJWTKeys(rows interface {
	Next() bool
	Scan(dest ...interface{}) error
}) ([]*entity.JWTKey, error) {
	var keys []*entity.JWTKey

	pgxRows, ok := rows.(interface {
		Next() bool
		Scan(dest ...interface{}) error
	})
	if !ok {
		return nil, fmt.Errorf("invalid rows type")
	}

	for pgxRows.Next() {
		var key entity.JWTKey
		err := pgxRows.Scan(
			&key.Kid,
			&key.PublicKey,
			&key.PrivateKey,
			&key.Status,
			&key.CreatedAt,
			&key.RotatedAt,
			&key.ExpiresAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan JWT key: %w", err)
		}
		keys = append(keys, &key)
	}

	if keys == nil {
		keys = []*entity.JWTKey{}
	}

	return keys, nil
}

var _ = errors.Is

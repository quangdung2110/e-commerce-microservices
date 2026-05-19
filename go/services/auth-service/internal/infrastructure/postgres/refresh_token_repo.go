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

type RefreshTokenRepository struct {
	pool *pgxpool.Pool
}

func NewRefreshTokenRepository(pool *pgxpool.Pool) *RefreshTokenRepository {
	return &RefreshTokenRepository{pool: pool}
}

func (r *RefreshTokenRepository) Create(ctx context.Context, token *entity.RefreshToken) error {
	sql := `
		INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, replaced_by,
		                          device_id, device_info, expires_at, revoked_at, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
	`

	_, err := r.pool.Exec(ctx, sql,
		token.ID,
		token.UserID,
		token.TokenHash,
		token.FamilyID,
		token.ReplacedBy,
		token.DeviceID,
		token.DeviceInfo,
		token.ExpiresAt,
		token.RevokedAt,
		token.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to create refresh token: %w", err)
	}

	return nil
}

func (r *RefreshTokenRepository) GetByID(ctx context.Context, id string) (*entity.RefreshToken, error) {
	sql := `
		SELECT id, user_id, token_hash, family_id, replaced_by, device_id,
		       device_info, expires_at, revoked_at, created_at
		FROM refresh_tokens
		WHERE id = $1
	`

	row := r.pool.QueryRow(ctx, sql, id)
	token, err := r.scanRefreshToken(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get refresh token by id: %w", err)
	}

	return token, nil
}

func (r *RefreshTokenRepository) GetByTokenHash(ctx context.Context, hash string) (*entity.RefreshToken, error) {
	sql := `
		SELECT id, user_id, token_hash, family_id, replaced_by, device_id,
		       device_info, expires_at, revoked_at, created_at
		FROM refresh_tokens
		WHERE token_hash = $1
	`

	row := r.pool.QueryRow(ctx, sql, hash)
	token, err := r.scanRefreshToken(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get refresh token by hash: %w", err)
	}

	return token, nil
}

func (r *RefreshTokenRepository) GetActiveByUserID(ctx context.Context, userID string) ([]*entity.RefreshToken, error) {
	sql := `
		SELECT id, user_id, token_hash, family_id, replaced_by, device_id,
		       device_info, expires_at, revoked_at, created_at
		FROM refresh_tokens
		WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > NOW()
		ORDER BY created_at DESC
	`

	rows, err := r.pool.Query(ctx, sql, userID)
	if err != nil {
		return nil, fmt.Errorf("failed to get active tokens: %w", err)
	}
	defer rows.Close()

	var tokens []*entity.RefreshToken
	for rows.Next() {
		token, err := r.scanRefreshTokenRows(rows)
		if err != nil {
			return nil, err
		}
		tokens = append(tokens, token)
	}

	return tokens, nil
}

func (r *RefreshTokenRepository) Revoke(ctx context.Context, id string) error {
	sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE id = $1`

	result, err := r.pool.Exec(ctx, sql, id)
	if err != nil {
		return fmt.Errorf("failed to revoke token: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *RefreshTokenRepository) RevokeByHash(ctx context.Context, hash string) error {
	sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = $1`

	_, err := r.pool.Exec(ctx, sql, hash)
	if err != nil {
		return fmt.Errorf("failed to revoke token by hash: %w", err)
	}

	return nil
}

func (r *RefreshTokenRepository) RevokeAllForUser(ctx context.Context, userID string) error {
	sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL`

	_, err := r.pool.Exec(ctx, sql, userID)
	if err != nil {
		return fmt.Errorf("failed to revoke all tokens: %w", err)
	}

	return nil
}

func (r *RefreshTokenRepository) RevokeFamily(ctx context.Context, familyID string) error {
	sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE family_id = $1 AND revoked_at IS NULL`

	_, err := r.pool.Exec(ctx, sql, familyID)
	if err != nil {
		return fmt.Errorf("failed to revoke family tokens: %w", err)
	}

	return nil
}

func (r *RefreshTokenRepository) UpdateReplacedBy(ctx context.Context, oldID, newID string) error {
	sql := `UPDATE refresh_tokens SET replaced_by = $2 WHERE id = $1`

	_, err := r.pool.Exec(ctx, sql, oldID, newID)
	if err != nil {
		return fmt.Errorf("failed to update replaced_by: %w", err)
	}

	return nil
}

func (r *RefreshTokenRepository) DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error) {
	sql := `
		DELETE FROM refresh_tokens
		WHERE (expires_at < $1 AND expires_at < NOW() - INTERVAL '7 days')
		   OR (revoked_at IS NOT NULL AND revoked_at < $1)
	`

	result, err := r.pool.Exec(ctx, sql, olderThan)
	if err != nil {
		return 0, fmt.Errorf("failed to delete expired tokens: %w", err)
	}

	return result.RowsAffected(), nil
}

func (r *RefreshTokenRepository) scanRefreshToken(row interface{ Scan(dest ...interface{}) error }) (*entity.RefreshToken, error) {
	var token entity.RefreshToken
	err := row.Scan(
		&token.ID,
		&token.UserID,
		&token.TokenHash,
		&token.FamilyID,
		&token.ReplacedBy,
		&token.DeviceID,
		&token.DeviceInfo,
		&token.ExpiresAt,
		&token.RevokedAt,
		&token.CreatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &token, nil
}

func (r *RefreshTokenRepository) scanRefreshTokenRows(rows interface {
	Next() bool
	Scan(dest ...interface{}) error
}) (*entity.RefreshToken, error) {
	var token entity.RefreshToken
	err := rows.Scan(
		&token.ID,
		&token.UserID,
		&token.TokenHash,
		&token.FamilyID,
		&token.ReplacedBy,
		&token.DeviceID,
		&token.DeviceInfo,
		&token.ExpiresAt,
		&token.RevokedAt,
		&token.CreatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &token, nil
}

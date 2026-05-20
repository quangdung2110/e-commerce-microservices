package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

var _ interface{} = (*UserRepository)(nil)

type UserRepository struct {
	pool *pgxpool.Pool
}

func NewUserRepository(pool *pgxpool.Pool) *UserRepository {
	return &UserRepository{pool: pool}
}

func (r *UserRepository) Create(ctx context.Context, user *entity.User) error {
	rolesJSON, err := json.Marshal(user.Roles)
	if err != nil {
		return fmt.Errorf("failed to marshal roles: %w", err)
	}

	sql := `
		INSERT INTO users (id, email, password_hash, roles, status, locked_at,
		                  failed_login_attempts, email_verified_at, created_at, updated_at, revision)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	`

	_, err = r.pool.Exec(ctx, sql,
		user.ID,
		user.Email.String(),
		user.PasswordHash,
		rolesJSON,
		user.Status,
		user.LockedAt,
		user.FailedLoginAttempts,
		user.EmailVerifiedAt,
		user.CreatedAt,
		user.UpdatedAt,
		user.Revision,
	)
	if err != nil {
		return fmt.Errorf("failed to create user: %w", err)
	}

	return nil
}

func (r *UserRepository) GetByID(ctx context.Context, id string) (*entity.User, error) {
	sql := `
		SELECT id, email, password_hash, roles, status, locked_at,
		       failed_login_attempts, email_verified_at, created_at, updated_at, revision
		FROM users
		WHERE id = $1
	`

	row := r.pool.QueryRow(ctx, sql, id)
	user, err := r.scanUser(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get user by id: %w", err)
	}

	return user, nil
}

func (r *UserRepository) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
	sql := `
		SELECT id, email, password_hash, roles, status, locked_at,
		       failed_login_attempts, email_verified_at, created_at, updated_at, revision
		FROM users
		WHERE email = $1
	`

	row := r.pool.QueryRow(ctx, sql, email)
	user, err := r.scanUser(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("failed to get user by email: %w", err)
	}

	return user, nil
}

func (r *UserRepository) Update(ctx context.Context, user *entity.User) error {
	rolesJSON, err := json.Marshal(user.Roles)
	if err != nil {
		return fmt.Errorf("failed to marshal roles: %w", err)
	}

	sql := `
		UPDATE users
		SET email = $2, password_hash = $3, roles = $4, status = $5,
		    locked_at = $6, failed_login_attempts = $7, email_verified_at = $8,
		    updated_at = NOW(), revision = revision + 1
		WHERE id = $1
	`

	result, err := r.pool.Exec(ctx, sql,
		user.ID,
		user.Email.String(),
		user.PasswordHash,
		rolesJSON,
		user.Status,
		user.LockedAt,
		user.FailedLoginAttempts,
		user.EmailVerifiedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to update user: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) UpdateStatus(ctx context.Context, id string, status string) error {
	sql := `UPDATE users SET status = $2, updated_at = NOW(), revision = revision + 1 WHERE id = $1`

	result, err := r.pool.Exec(ctx, sql, id, status)
	if err != nil {
		return fmt.Errorf("failed to update status: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) LockAccount(ctx context.Context, id string) error {
	sql := `
		UPDATE users
		SET status = 'LOCKED', locked_at = NOW(), failed_login_attempts = 0,
		    updated_at = NOW(), revision = revision + 1
		WHERE id = $1 AND status = 'ACTIVE'
	`

	result, err := r.pool.Exec(ctx, sql, id)
	if err != nil {
		return fmt.Errorf("failed to lock account: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) UnlockAccount(ctx context.Context, id string) error {
	sql := `
		UPDATE users
		SET status = 'ACTIVE', locked_at = NULL, failed_login_attempts = 0,
		    updated_at = NOW(), revision = revision + 1
		WHERE id = $1 AND status = 'LOCKED'
	`

	result, err := r.pool.Exec(ctx, sql, id)
	if err != nil {
		return fmt.Errorf("failed to unlock account: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) BanAccount(ctx context.Context, id string) error {
	sql := `
		UPDATE users
		SET status = 'BANNED', updated_at = NOW(), revision = revision + 1
		WHERE id = $1
	`

	result, err := r.pool.Exec(ctx, sql, id)
	if err != nil {
		return fmt.Errorf("failed to ban account: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]*entity.User, error) {
	sql := `
		SELECT id, email, password_hash, roles, status, locked_at,
		       failed_login_attempts, email_verified_at, created_at, updated_at, revision
		FROM users
		WHERE status = 'LOCKED' AND locked_at <= $1
		ORDER BY locked_at ASC
		LIMIT $2
	`

	threshold := time.Now().Add(-lockDuration)
	rows, err := r.pool.Query(ctx, sql, threshold, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get locked users: %w", err)
	}
	defer rows.Close()

	var users []*entity.User
	for rows.Next() {
		user, err := r.scanUser(rows)
		if err != nil {
			return nil, err
		}
		users = append(users, user)
	}

	return users, nil
}

func (r *UserRepository) Delete(ctx context.Context, id string) error {
	sql := `DELETE FROM users WHERE id = $1`

	result, err := r.pool.Exec(ctx, sql, id)
	if err != nil {
		return fmt.Errorf("failed to delete user: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *UserRepository) scanUser(row interface {
	Scan(dest ...interface{}) error
}) (*entity.User, error) {
	var user entity.User
	var emailStr string
	var rolesJSON []byte

	err := row.Scan(
		&user.ID,
		&emailStr,
		&user.PasswordHash,
		&rolesJSON,
		&user.Status,
		&user.LockedAt,
		&user.FailedLoginAttempts,
		&user.EmailVerifiedAt,
		&user.CreatedAt,
		&user.UpdatedAt,
		&user.Revision,
	)
	if err != nil {
		return nil, err
	}

	email, err := vo.NewEmail(emailStr)
	if err != nil {
		return nil, fmt.Errorf("failed to create email value object: %w", err)
	}
	user.Email = *email

	if err := json.Unmarshal(rolesJSON, &user.Roles); err != nil {
		return nil, fmt.Errorf("failed to unmarshal roles: %w", err)
	}

	return &user, nil
}

func (r *UserRepository) scanUserRows(rows interface {
	Next() bool
	Scan(dest ...interface{}) error
}) (*entity.User, error) {
	var user entity.User
	var emailStr string
	var rolesJSON []byte

	err := rows.Scan(
		&user.ID,
		&emailStr,
		&user.PasswordHash,
		&rolesJSON,
		&user.Status,
		&user.LockedAt,
		&user.FailedLoginAttempts,
		&user.EmailVerifiedAt,
		&user.CreatedAt,
		&user.UpdatedAt,
		&user.Revision,
	)
	if err != nil {
		return nil, err
	}

	email, err := vo.NewEmail(emailStr)
	if err != nil {
		return nil, fmt.Errorf("failed to create email value object: %w", err)
	}
	user.Email = *email

	if err := json.Unmarshal(rolesJSON, &user.Roles); err != nil {
		return nil, fmt.Errorf("failed to unmarshal roles: %w", err)
	}

	return &user, nil
}

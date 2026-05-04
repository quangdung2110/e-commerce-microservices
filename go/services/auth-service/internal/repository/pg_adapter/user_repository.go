package pg_adapter

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// PostgresUserRepository implements UserRepository using PostgreSQL
type PostgresUserRepository struct {
	pool   *pgxpool.Pool
	logger *zap.Logger
}

// NewPostgresUserRepository creates a new PostgreSQL user repository
func NewPostgresUserRepository(pool *pgxpool.Pool, logger *zap.Logger) repository.UserRepository {
	return &PostgresUserRepository{
		pool:   pool,
		logger: logger,
	}
}

// LockAccount locks a user account due to failed login attempts
func (r *PostgresUserRepository) LockAccount(ctx context.Context, userID uuid.UUID, lockedAt time.Time) error {
	query := `
		UPDATE users 
		SET status = 'LOCKED', 
		    locked_at = $2, 
		    failed_login_attempts = 0,
		    updated_at = NOW()
		WHERE id = $1 AND status = 'ACTIVE'
	`

	result, err := r.pool.Exec(ctx, query, userID, pgtype.Timestamptz{Time: lockedAt, Valid: true})
	if err != nil {
		r.logger.Error("Failed to lock account",
			zap.String("user_id", userID.String()),
			zap.Error(err))
		return err
	}

	if result.RowsAffected() == 0 {
		r.logger.Warn("Account not locked - user not found or already locked",
			zap.String("user_id", userID.String()))
	}

	return nil
}

// UnlockAccount unlocks a user account
func (r *PostgresUserRepository) UnlockAccount(ctx context.Context, userID uuid.UUID) error {
	query := `
		UPDATE users 
		SET status = 'ACTIVE', 
		    locked_at = NULL, 
		    failed_login_attempts = 0,
		    updated_at = NOW()
		WHERE id = $1 AND status = 'LOCKED'
	`

	result, err := r.pool.Exec(ctx, query, userID)
	if err != nil {
		r.logger.Error("Failed to unlock account",
			zap.String("user_id", userID.String()),
			zap.Error(err))
		return err
	}

	if result.RowsAffected() == 0 {
		r.logger.Warn("Account not unlocked - user not found or not locked",
			zap.String("user_id", userID.String()))
	}

	return nil
}

// GetLockedUsers retrieves all users that are locked and ready for auto-unlock
func (r *PostgresUserRepository) GetLockedUsers(ctx context.Context, unlockAfter time.Time, limit int) ([]*entity.User, error) {
	query := `
		SELECT id, email, status, locked_at, failed_login_attempts
		FROM users
		WHERE status = 'LOCKED' 
		  AND locked_at IS NOT NULL 
		  AND locked_at <= $1
		ORDER BY locked_at ASC
		LIMIT $2
	`

	rows, err := r.pool.Query(ctx, query, pgtype.Timestamptz{Time: unlockAfter, Valid: true}, limit)
	if err != nil {
		r.logger.Error("Failed to get locked users", zap.Error(err))
		return nil, err
	}
	defer rows.Close()

	var users []*entity.User
	for rows.Next() {
		user := &entity.User{}
		var lockedAt pgtype.Timestamptz
		var email string

		err := rows.Scan(
			&user.ID,
			&email,
			&user.Status,
			&lockedAt,
			&user.FailedLoginAttempts,
		)
		if err != nil {
			r.logger.Error("Failed to scan user row", zap.Error(err))
			continue
		}

		user.Email = email
		if lockedAt.Valid {
			user.LockedAt = &lockedAt.Time
		}

		users = append(users, user)
	}

	if err := rows.Err(); err != nil {
		r.logger.Error("Error iterating user rows", zap.Error(err))
		return nil, err
	}

	return users, nil
}

// GetByID retrieves a user by ID
func (r *PostgresUserRepository) GetByID(ctx context.Context, userID uuid.UUID) (*entity.User, error) {
	query := `
		SELECT id, email, password_hash, roles, status, locked_at, failed_login_attempts, 
		       email_verified_at, created_at, updated_at
		FROM users
		WHERE id = $1
	`

	row := r.pool.QueryRow(ctx, query, userID)

	user := &entity.User{}
	var lockedAt pgtype.Timestamptz
	var emailVerifiedAt pgtype.Timestamptz
	var passwordHash string
	var roles []byte

	err := row.Scan(
		&user.ID,
		&user.Email,
		&passwordHash,
		&roles,
		&user.Status,
		&lockedAt,
		&user.FailedLoginAttempts,
		&emailVerifiedAt,
		&user.CreatedAt,
		&user.UpdatedAt,
	)

	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		r.logger.Error("Failed to get user by ID",
			zap.String("user_id", userID.String()),
			zap.Error(err))
		return nil, err
	}

	user.PasswordHash = passwordHash

	if lockedAt.Valid {
		user.LockedAt = &lockedAt.Time
	}

	if emailVerifiedAt.Valid {
		user.EmailVerifiedAt = &emailVerifiedAt.Time
	}

	return user, nil
}

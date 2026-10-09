package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

var _ port.AccountRepository = (*AccountRepository)(nil)

// AccountRepository stores identities in auth.accounts and auth.account_roles.
type AccountRepository struct {
	repository
}

// NewAccountRepository wires the account repository.
func NewAccountRepository(pool *pgxpool.Pool) *AccountRepository {
	return &AccountRepository{repository{pool: pool}}
}

const accountColumns = `
	a.id::text,
	a.email,
	a.email_normalized,
	a.password_hash,
	a.status::text,
	a.failed_login_count,
	a.last_failed_login_at,
	a.locked_until,
	a.password_changed_at,
	a.deleted_at,
	a.hard_delete_after,
	a.deletion_reason::text,
	a.created_at,
	a.updated_at,
	COALESCE(
		ARRAY(
			SELECT r.role_code::text
			FROM auth.account_roles r
			WHERE r.account_id = a.id
			ORDER BY r.role_code
		),
		ARRAY[]::text[]
	) AS roles`

// Create inserts the account and its roles in one transaction.
func (r *AccountRepository) Create(ctx context.Context, account *entity.Account) error {
	insertAccount := `
		INSERT INTO auth.accounts (
			id, email, email_normalized, password_hash, status,
			failed_login_count, last_failed_login_at, locked_until, password_changed_at,
			deleted_at, hard_delete_after, deletion_reason, created_at, updated_at
		) VALUES (
			$1::uuid, $2, $3, $4, $5::auth.account_status,
			$6, $7, $8, $9,
			$10, $11, $12::auth.deletion_reason, $13, $14
		)
		RETURNING updated_at`

	insertRole := `
		INSERT INTO auth.account_roles (account_id, role_code)
		VALUES ($1::uuid, $2::auth.role_code)
		ON CONFLICT DO NOTHING`

	return withinTx(ctx, r.pool, func(txCtx context.Context) error {
		db := r.db(txCtx)
		var revision time.Time
		if err := db.QueryRow(txCtx, insertAccount,
			account.ID.String(),
			nullableString(account.Email.String()),
			nullableString(account.Email.Normalized()),
			nullableString(account.PasswordHash),
			account.Status.String(),
			account.FailedLoginCount,
			account.LastFailedLoginAt,
			account.LockedUntil,
			account.PasswordChangedAt,
			account.DeletedAt,
			account.HardDeleteAfter,
			deletionReasonValue(account.DeletionReason),
			account.CreatedAt,
			account.UpdatedAt,
		).Scan(&revision); err != nil {
			return mapError(err)
		}
		// The database keeps microseconds; the saved copy must carry the version
		// the row really has so that it can be updated right away.
		account.Revision = revision
		account.UpdatedAt = revision

		for _, role := range account.Roles {
			if _, err := db.Exec(txCtx, insertRole, account.ID.String(), role.String()); err != nil {
				return mapError(err)
			}
		}
		return nil
	})
}

// GetByID loads one account with its roles.
func (r *AccountRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Account, error) {
	query := `SELECT ` + accountColumns + ` FROM auth.accounts a WHERE a.id = $1::uuid`
	return scanAccount(r.db(ctx).QueryRow(ctx, query, id.String()))
}

// GetByNormalizedEmail loads one account by its unique identity key.
func (r *AccountRepository) GetByNormalizedEmail(ctx context.Context, normalizedEmail string) (*entity.Account, error) {
	query := `SELECT ` + accountColumns + ` FROM auth.accounts a WHERE a.email_normalized = $1`
	return scanAccount(r.db(ctx).QueryRow(ctx, query, normalizedEmail))
}

// ExistsByNormalizedEmail answers the uniqueness pre-check.
func (r *AccountRepository) ExistsByNormalizedEmail(ctx context.Context, normalizedEmail string) (bool, error) {
	var exists bool
	err := r.db(ctx).QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM auth.accounts WHERE email_normalized = $1)`,
		normalizedEmail,
	).Scan(&exists)
	if err != nil {
		return false, mapError(err)
	}
	return exists, nil
}

// Update persists the mutable columns, but only over the row version the caller
// loaded. updated_at is maintained by the trigger defined in the schema and is
// the optimistic-concurrency token (entity.Account.Revision): a login that read
// the row, spent its time verifying a password and then wrote the whole row back
// would otherwise undo a password change, an email change or a deletion that
// committed in between. A row that moved on is ErrConcurrentUpdate; the caller
// reloads and re-applies its change.
func (r *AccountRepository) Update(ctx context.Context, account *entity.Account) error {
	query := `
		UPDATE auth.accounts SET
			email = $2,
			email_normalized = $3,
			password_hash = $4,
			status = $5::auth.account_status,
			failed_login_count = $6,
			last_failed_login_at = $7,
			locked_until = $8,
			password_changed_at = $9,
			deleted_at = $10,
			hard_delete_after = $11,
			deletion_reason = $12::auth.deletion_reason
		WHERE id = $1::uuid AND updated_at = $13
		RETURNING updated_at`

	var revision time.Time
	err := r.db(ctx).QueryRow(ctx, query,
		account.ID.String(),
		nullableString(account.Email.String()),
		nullableString(account.Email.Normalized()),
		nullableString(account.PasswordHash),
		account.Status.String(),
		account.FailedLoginCount,
		account.LastFailedLoginAt,
		account.LockedUntil,
		account.PasswordChangedAt,
		account.DeletedAt,
		account.HardDeleteAfter,
		deletionReasonValue(account.DeletionReason),
		account.Revision,
	).Scan(&revision)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			return mapError(err)
		}
		// Either the account is gone or its version moved on.
		var exists bool
		if err := r.db(ctx).QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM auth.accounts WHERE id = $1::uuid)`,
			account.ID.String(),
		).Scan(&exists); err != nil {
			return mapError(err)
		}
		if !exists {
			return apperr.ErrAccountNotFound
		}
		return apperr.ErrConcurrentUpdate
	}
	account.Revision = revision
	account.UpdatedAt = revision
	return nil
}

// ListExpiredLocks returns locked accounts whose window elapsed, oldest first.
func (r *AccountRepository) ListExpiredLocks(ctx context.Context, now time.Time, limit int) ([]*entity.Account, error) {
	query := `
		SELECT ` + accountColumns + `
		FROM auth.accounts a
		WHERE a.status = 'LOCKED' AND a.locked_until <= $1
		ORDER BY a.locked_until
		LIMIT $2`

	rows, err := r.db(ctx).Query(ctx, query, now.UTC(), limit)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	var accounts []*entity.Account
	for rows.Next() {
		account, err := scanAccount(rows)
		if err != nil {
			return nil, err
		}
		accounts = append(accounts, account)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return accounts, nil
}

// ListDueForHardDelete returns the accounts the compliance worker must remove.
func (r *AccountRepository) ListDueForHardDelete(ctx context.Context, now time.Time, limit int) ([]uuid.UUID, error) {
	query := `
		SELECT id::text
		FROM auth.accounts
		WHERE status = 'DELETED' AND hard_delete_after <= $1
		ORDER BY hard_delete_after
		LIMIT $2`

	rows, err := r.db(ctx).Query(ctx, query, now.UTC(), limit)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	var ids []uuid.UUID
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, mapError(err)
		}
		id, err := uuid.Parse(raw)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return ids, nil
}

// HardDelete removes the rows permanently. Roles and refresh tokens cascade.
func (r *AccountRepository) HardDelete(ctx context.Context, ids []uuid.UUID) (int64, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	raw := make([]string, 0, len(ids))
	for _, id := range ids {
		raw = append(raw, id.String())
	}

	tag, err := r.db(ctx).Exec(ctx,
		`DELETE FROM auth.accounts WHERE id = ANY($1::uuid[])`, raw)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// rowScanner covers both pgx.Row and pgx.Rows.
type rowScanner interface {
	Scan(dest ...any) error
}

func scanAccount(row rowScanner) (*entity.Account, error) {
	var (
		rawID             string
		email             *string
		emailNormalized   *string
		passwordHash      *string
		rawStatus         string
		failedLoginCount  int
		lastFailedLoginAt *time.Time
		lockedUntil       *time.Time
		passwordChangedAt time.Time
		deletedAt         *time.Time
		hardDeleteAfter   *time.Time
		rawDeletionReason *string
		createdAt         time.Time
		updatedAt         time.Time
		rawRoles          []string
	)

	err := row.Scan(
		&rawID, &email, &emailNormalized, &passwordHash, &rawStatus,
		&failedLoginCount, &lastFailedLoginAt, &lockedUntil, &passwordChangedAt,
		&deletedAt, &hardDeleteAfter, &rawDeletionReason, &createdAt, &updatedAt, &rawRoles,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperr.ErrAccountNotFound
		}
		return nil, mapError(err)
	}

	id, err := uuid.Parse(rawID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	status, err := vo.ParseAccountStatus(rawStatus)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	roles := make([]vo.Role, 0, len(rawRoles))
	for _, raw := range rawRoles {
		role, err := vo.ParseRole(raw)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		roles = append(roles, role)
	}

	account := &entity.Account{
		ID:                id,
		Email:             vo.EmailFromStorage(derefString(email), derefString(emailNormalized)),
		PasswordHash:      derefString(passwordHash),
		Status:            status,
		Roles:             roles,
		FailedLoginCount:  failedLoginCount,
		LastFailedLoginAt: lastFailedLoginAt,
		LockedUntil:       lockedUntil,
		PasswordChangedAt: passwordChangedAt,
		DeletedAt:         deletedAt,
		HardDeleteAfter:   hardDeleteAfter,
		CreatedAt:         createdAt,
		UpdatedAt:         updatedAt,
		Revision:          updatedAt,
	}

	if rawDeletionReason != nil {
		reason, err := vo.ParseDeletionReason(*rawDeletionReason)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		account.DeletionReason = &reason
	}
	return account, nil
}

func nullableString(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func derefString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func deletionReasonValue(reason *vo.DeletionReason) *string {
	if reason == nil {
		return nil
	}
	raw := reason.String()
	return &raw
}

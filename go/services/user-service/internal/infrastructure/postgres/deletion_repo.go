package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/port"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

var _ port.DeletionRepository = (*DeletionRepository)(nil)

// DeletionRepository persists "user".user_deletions.
//
// The table has no foreign key on purpose: the record has to outlive the
// profile row the purge worker deletes, and its unique user_id is the only
// idempotency key the deletion flow needs (docs/user/data.md §2 and §7).
type DeletionRepository struct {
	repository
}

// NewDeletionRepository wires the retention repository.
func NewDeletionRepository(pool *pgxpool.Pool) *DeletionRepository {
	return &DeletionRepository{repository{pool: pool}}
}

const deletionColumns = `id, user_id, deleted_at, hard_delete_after, reason, created_at`

// Insert records one deletion. A duplicate USER_DELETED hits the unique user_id
// and is reported as "not created", which keeps the original deadline
// (USER-BR-006).
func (r *DeletionRepository) Insert(ctx context.Context, deletion *entity.Deletion) (bool, error) {
	const query = `
		INSERT INTO "user".user_deletions (id, user_id, deleted_at, hard_delete_after, reason, created_at)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (user_id) DO NOTHING`

	tag, err := r.db(ctx).Exec(ctx, query,
		deletion.ID,
		deletion.UserID,
		deletion.DeletedAt,
		deletion.HardDeleteAfter,
		deletion.Reason.String(),
		deletion.CreatedAt,
	)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// ExistsForUser reports whether the user is already in the retention window.
// The bootstrap consumer uses it to keep deletion terminal.
func (r *DeletionRepository) ExistsForUser(ctx context.Context, userID uuid.UUID) (bool, error) {
	const query = `SELECT EXISTS (SELECT 1 FROM "user".user_deletions WHERE user_id = $1)`

	var exists bool
	if err := r.db(ctx).QueryRow(ctx, query, userID).Scan(&exists); err != nil {
		return false, mapError(err)
	}
	return exists, nil
}

// ListDue returns the records whose deadline has passed, oldest deadline first.
// It takes no lock: the worker claims each record separately so one slow record
// cannot hold a lock over the whole batch.
func (r *DeletionRepository) ListDue(ctx context.Context, now time.Time, limit int) ([]*entity.Deletion, error) {
	const query = `
		SELECT ` + deletionColumns + `
		FROM "user".user_deletions
		WHERE hard_delete_after <= $1
		ORDER BY hard_delete_after
		LIMIT $2`

	rows, err := r.db(ctx).Query(ctx, query, now.UTC(), limit)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	deletions := make([]*entity.Deletion, 0, limit)
	for rows.Next() {
		deletion, err := scanDeletion(rows)
		if err != nil {
			return nil, mapError(err)
		}
		deletions = append(deletions, deletion)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return deletions, nil
}

// ClaimDue locks one due record for the current transaction. SKIP LOCKED makes
// a record another replica is already purging return nil instead of blocking
// (WF-USER-007).
func (r *DeletionRepository) ClaimDue(ctx context.Context, deletionID uuid.UUID, now time.Time) (*entity.Deletion, error) {
	const query = `
		SELECT ` + deletionColumns + `
		FROM "user".user_deletions
		WHERE id = $1 AND hard_delete_after <= $2
		FOR UPDATE SKIP LOCKED`

	rows, err := r.db(ctx).Query(ctx, query, deletionID, now.UTC())
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return nil, mapError(err)
		}
		return nil, nil
	}

	deletion, err := scanDeletion(rows)
	if err != nil {
		return nil, mapError(err)
	}
	return deletion, nil
}

// Delete removes the retention record once the profile has been purged.
func (r *DeletionRepository) Delete(ctx context.Context, deletionID uuid.UUID) error {
	const query = `DELETE FROM "user".user_deletions WHERE id = $1`

	if _, err := r.db(ctx).Exec(ctx, query, deletionID); err != nil {
		return mapError(err)
	}
	return nil
}

// CountOverdueBefore reports how many records passed their deadline before the
// cutoff, which is the backlog the compliance alert watches.
func (r *DeletionRepository) CountOverdueBefore(ctx context.Context, cutoff time.Time) (int64, error) {
	const query = `SELECT count(*) FROM "user".user_deletions WHERE hard_delete_after <= $1`

	var count int64
	if err := r.db(ctx).QueryRow(ctx, query, cutoff.UTC()).Scan(&count); err != nil {
		return 0, mapError(err)
	}
	return count, nil
}

func scanDeletion(rows pgx.Rows) (*entity.Deletion, error) {
	var (
		deletion entity.Deletion
		reason   string
	)
	err := rows.Scan(
		&deletion.ID,
		&deletion.UserID,
		&deletion.DeletedAt,
		&deletion.HardDeleteAfter,
		&reason,
		&deletion.CreatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperr.ErrDeletionAlreadyProcessed
		}
		return nil, err
	}

	parsedReason, err := vo.ParseDeletionReason(reason)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	deletion.Reason = parsedReason
	deletion.DeletedAt = deletion.DeletedAt.UTC()
	deletion.HardDeleteAfter = deletion.HardDeleteAfter.UTC()
	deletion.CreatedAt = deletion.CreatedAt.UTC()
	return &deletion, nil
}

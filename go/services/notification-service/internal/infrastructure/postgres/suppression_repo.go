package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.SuppressionRepository = (*SuppressionRepository)(nil)

// SuppressionRepository is the do-not-mail ledger (09-data-model.md §6),
// keyed by user_id for Decide and by email_hash for the worker check.
type SuppressionRepository struct {
	q querier
}

const suppressionColumns = `id, user_id, email_hash, reason, source, until, evidence_event_id, superseded_at, created_at`

func (r *SuppressionRepository) Insert(ctx context.Context, s *entity.Suppression) error {
	const query = `
		INSERT INTO suppression (` + suppressionColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, COALESCE($9::timestamptz, now()))`
	var createdAt *time.Time
	if !s.CreatedAt.IsZero() {
		createdAt = &s.CreatedAt
	}
	_, err := r.q.Exec(ctx, query, s.ID, s.UserID, s.EmailHash, string(s.Reason), s.Source,
		s.Until, s.EvidenceEventID, s.SupersededAt, createdAt)
	return mapError(err)
}

func (r *SuppressionRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Suppression, error) {
	rows, err := r.q.Query(ctx, `SELECT `+suppressionColumns+` FROM suppression WHERE id = $1`, id)
	if err != nil {
		return nil, mapError(err)
	}
	list, err := collect(rows, scanSuppression)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, mapError(pgx.ErrNoRows)
	}
	return &list[0], nil
}

// ActiveByUser returns non-superseded rows; the caller checks Until against
// its own clock so a frozen test clock and the DB never disagree.
func (r *SuppressionRepository) ActiveByUser(ctx context.Context, userID uuid.UUID) ([]entity.Suppression, error) {
	return r.many(ctx, `
		SELECT `+suppressionColumns+` FROM suppression
		WHERE user_id = $1 AND superseded_at IS NULL
		ORDER BY created_at DESC`, userID)
}

func (r *SuppressionRepository) ActiveByHash(ctx context.Context, emailHash string) ([]entity.Suppression, error) {
	return r.many(ctx, `
		SELECT `+suppressionColumns+` FROM suppression
		WHERE email_hash = $1 AND superseded_at IS NULL
		ORDER BY created_at DESC`, emailHash)
}

// MarkSuperseded records that the user changed address (08-decide.md §4).
func (r *SuppressionRepository) MarkSuperseded(ctx context.Context, id uuid.UUID, at time.Time) error {
	tag, err := r.q.Exec(ctx, `UPDATE suppression SET superseded_at = $2 WHERE id = $1 AND superseded_at IS NULL`, id, at)
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		// Either absent or already superseded; both are fine for the caller.
		var exists bool
		if err := r.q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM suppression WHERE id = $1)`, id).Scan(&exists); err != nil {
			return mapError(err)
		}
		if !exists {
			return mapError(pgx.ErrNoRows)
		}
	}
	return nil
}

func (r *SuppressionRepository) Delete(ctx context.Context, id uuid.UUID) error {
	tag, err := r.q.Exec(ctx, `DELETE FROM suppression WHERE id = $1`, id)
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		return mapError(pgx.ErrNoRows)
	}
	return nil
}

// DetachUser keeps the hash (a dead address stays dead) and drops the user
// link (09-data-model.md §10).
func (r *SuppressionRepository) DetachUser(ctx context.Context, userID uuid.UUID) (int64, error) {
	tag, err := r.q.Exec(ctx, `UPDATE suppression SET user_id = NULL WHERE user_id = $1`, userID)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func (r *SuppressionRepository) many(ctx context.Context, query string, args ...any) ([]entity.Suppression, error) {
	rows, err := r.q.Query(ctx, query, args...)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanSuppression)
}

func scanSuppression(rows pgx.Rows) (entity.Suppression, error) {
	var (
		s      entity.Suppression
		reason string
	)
	err := rows.Scan(&s.ID, &s.UserID, &s.EmailHash, &reason, &s.Source, &s.Until,
		&s.EvidenceEventID, &s.SupersededAt, &s.CreatedAt)
	if err != nil {
		return s, err
	}
	s.Reason = entity.SuppressionReason(reason)
	return s, nil
}

package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/port"
)

var _ port.ProjectionRepository = (*ProjectionRepository)(nil)

// ProjectionRepository serves the daily reconciliation sweep (ADR-007). It only
// reads current state: the design has no outbox, no checkpoint table and no
// event deduplication table.
type ProjectionRepository struct {
	repository
}

// NewProjectionRepository wires the reconciliation reader.
func NewProjectionRepository(pool *pgxpool.Pool) *ProjectionRepository {
	return &ProjectionRepository{repository{pool: pool}}
}

// ListActive returns one batch of active users ordered by id, starting after
// the cursor. Users holding a retention record are excluded: deletion is
// terminal, so a corrective snapshot must never revive a purged projection.
//
// The join is a LEFT JOIN because a profile whose preferences row is somehow
// missing still needs its display name reconciled.
func (r *ProjectionRepository) ListActive(ctx context.Context, afterUserID uuid.UUID, limit int) ([]port.ProjectionSnapshot, error) {
	const query = `
		SELECT p.id,
		       p.first_name,
		       p.last_name,
		       p.updated_at,
		       pref.email_enabled,
		       pref.in_app_enabled,
		       pref.marketing_enabled,
		       pref.updated_at
		FROM "user".user_profiles p
		LEFT JOIN "user".user_preferences pref ON pref.user_id = p.id
		WHERE p.id > $1
		  AND NOT EXISTS (
		      SELECT 1 FROM "user".user_deletions d WHERE d.user_id = p.id
		  )
		ORDER BY p.id
		LIMIT $2`

	rows, err := r.db(ctx).Query(ctx, query, afterUserID, limit)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	snapshots := make([]port.ProjectionSnapshot, 0, limit)
	for rows.Next() {
		var (
			userID           uuid.UUID
			firstName        *string
			lastName         *string
			profileUpdatedAt time.Time
			emailEnabled     *bool
			inAppEnabled     *bool
			marketingEnabled *bool
			prefsUpdatedAt   *time.Time
		)
		if err := rows.Scan(
			&userID,
			&firstName,
			&lastName,
			&profileUpdatedAt,
			&emailEnabled,
			&inAppEnabled,
			&marketingEnabled,
			&prefsUpdatedAt,
		); err != nil {
			return nil, mapError(err)
		}

		// The display name is derived by the same domain rule the API and the
		// live event path use, including the neutral fallback.
		profile := entity.Profile{ID: userID, FirstName: firstName, LastName: lastName}
		snapshot := port.ProjectionSnapshot{
			UserID:           userID,
			SenderName:       profile.DisplayName(),
			ProfileUpdatedAt: profileUpdatedAt.UTC(),
		}
		if emailEnabled != nil && inAppEnabled != nil && marketingEnabled != nil && prefsUpdatedAt != nil {
			snapshot.Preferences = &port.PreferencesSnapshot{
				EmailEnabled:     *emailEnabled,
				InAppEnabled:     *inAppEnabled,
				MarketingEnabled: *marketingEnabled,
				UpdatedAt:        prefsUpdatedAt.UTC(),
			}
		}
		snapshots = append(snapshots, snapshot)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return snapshots, nil
}

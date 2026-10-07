package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.PreferenceRepository = (*PreferenceRepository)(nil)

// PreferenceRepository is the replica of t.user.events preferences
// (09-data-model.md §6). Writes are compare-and-set on version so an old
// event replayed late never overwrites a newer state (05-ordering.md §6).
type PreferenceRepository struct {
	q     querier
	clock clock.Clock
}

const preferenceColumns = `user_id, email_enabled, in_app_enabled, marketing_enabled, timezone, locale, version, synced_at`

func (r *PreferenceRepository) Get(ctx context.Context, userID uuid.UUID) (*entity.Preference, error) {
	var p entity.Preference
	err := r.q.QueryRow(ctx, `SELECT `+preferenceColumns+` FROM user_preference WHERE user_id = $1`, userID).
		Scan(&p.UserID, &p.EmailEnabled, &p.InAppEnabled, &p.MarketingEnabled, &p.Timezone, &p.Locale, &p.Version, &p.SyncedAt)
	if err != nil {
		return nil, mapError(err)
	}
	return &p, nil
}

// Upsert applies an event; the ON CONFLICT ... WHERE guard makes a stale
// version a no-op (0 rows → false).
func (r *PreferenceRepository) Upsert(ctx context.Context, p *entity.Preference) (bool, error) {
	syncedAt := p.SyncedAt
	if syncedAt.IsZero() {
		syncedAt = r.clock.Now()
	}
	const query = `
		INSERT INTO user_preference (` + preferenceColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (user_id) DO UPDATE
		SET email_enabled     = EXCLUDED.email_enabled,
		    in_app_enabled    = EXCLUDED.in_app_enabled,
		    marketing_enabled = EXCLUDED.marketing_enabled,
		    timezone          = EXCLUDED.timezone,
		    locale            = EXCLUDED.locale,
		    version           = EXCLUDED.version,
		    synced_at         = EXCLUDED.synced_at
		WHERE user_preference.version < EXCLUDED.version`
	tag, err := r.q.Exec(ctx, query, p.UserID, p.EmailEnabled, p.InAppEnabled, p.MarketingEnabled,
		p.Timezone, p.Locale, p.Version, syncedAt)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// DisableMarketing is the complaint action (03-tracking.md §5.2): marketing
// off regardless of version, and version bumped only upwards so a later
// preferences event with a higher version still wins.
func (r *PreferenceRepository) DisableMarketing(ctx context.Context, userID uuid.UUID, version int64, now time.Time) error {
	def := entity.DefaultPreference(userID)
	const query = `
		INSERT INTO user_preference (` + preferenceColumns + `)
		VALUES ($1, $2, $3, false, NULL, NULL, $4, $5)
		ON CONFLICT (user_id) DO UPDATE
		SET marketing_enabled = false,
		    version           = GREATEST(user_preference.version, EXCLUDED.version),
		    synced_at         = EXCLUDED.synced_at`
	_, err := r.q.Exec(ctx, query, userID, def.EmailEnabled, def.InAppEnabled, version, now)
	return mapError(err)
}

func (r *PreferenceRepository) Delete(ctx context.Context, userID uuid.UUID) error {
	_, err := r.q.Exec(ctx, `DELETE FROM user_preference WHERE user_id = $1`, userID)
	return mapError(err)
}

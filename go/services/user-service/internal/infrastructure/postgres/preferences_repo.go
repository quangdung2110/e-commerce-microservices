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

var _ port.PreferencesRepository = (*PreferencesRepository)(nil)

// PreferencesRepository persists "user".user_preferences, the 1:1 companion of
// a profile.
type PreferencesRepository struct {
	repository
}

// NewPreferencesRepository wires the preferences repository.
func NewPreferencesRepository(pool *pgxpool.Pool) *PreferencesRepository {
	return &PreferencesRepository{repository{pool: pool}}
}

// Insert writes the bootstrap defaults. ON CONFLICT DO NOTHING keeps a replayed
// verification event from resetting an opt-out the owner already made
// (USER-BR-005).
func (r *PreferencesRepository) Insert(ctx context.Context, preferences *entity.Preferences) (bool, error) {
	const query = `
		INSERT INTO "user".user_preferences (
			user_id, language, currency, email_enabled, in_app_enabled, marketing_enabled, updated_at
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (user_id) DO NOTHING`

	tag, err := r.db(ctx).Exec(ctx, query,
		preferences.UserID,
		preferences.Language.String(),
		preferences.Currency.String(),
		preferences.EmailEnabled,
		preferences.InAppEnabled,
		preferences.MarketingEnabled,
		preferences.UpdatedAt,
	)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// GetByUser reads the preferences of one profile.
func (r *PreferencesRepository) GetByUser(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error) {
	const query = `
		SELECT user_id, language, currency, email_enabled, in_app_enabled, marketing_enabled, updated_at
		FROM "user".user_preferences
		WHERE user_id = $1`

	var (
		preferences entity.Preferences
		language    string
		currency    string
	)
	err := r.db(ctx).QueryRow(ctx, query, userID).Scan(
		&preferences.UserID,
		&language,
		&currency,
		&preferences.EmailEnabled,
		&preferences.InAppEnabled,
		&preferences.MarketingEnabled,
		&preferences.UpdatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// Preferences exist exactly when the profile does, so a missing row
			// is reported as a missing profile rather than as an empty result.
			return nil, apperr.ErrProfileNotFound
		}
		return nil, mapError(err)
	}

	preferences.Language = vo.Language(language)
	preferences.Currency = vo.Currency(currency)
	preferences.UpdatedAt = preferences.UpdatedAt.UTC()
	return &preferences, nil
}

// Update writes the three notification flags. Language and currency are fixed
// by the contract and are never part of an update.
func (r *PreferencesRepository) Update(ctx context.Context, preferences *entity.Preferences) error {
	const query = `
		UPDATE "user".user_preferences
		SET email_enabled = $2,
		    in_app_enabled = $3,
		    marketing_enabled = $4
		WHERE user_id = $1
		RETURNING updated_at`

	var updatedAt time.Time
	err := r.db(ctx).QueryRow(ctx, query,
		preferences.UserID,
		preferences.EmailEnabled,
		preferences.InAppEnabled,
		preferences.MarketingEnabled,
	).Scan(&updatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return apperr.ErrProfileNotFound
		}
		return mapError(err)
	}

	preferences.UpdatedAt = updatedAt.UTC()
	return nil
}

// DeleteForUser removes the preferences of an anonymized account.
func (r *PreferencesRepository) DeleteForUser(ctx context.Context, userID uuid.UUID) (bool, error) {
	const query = `DELETE FROM "user".user_preferences WHERE user_id = $1`

	tag, err := r.db(ctx).Exec(ctx, query, userID)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

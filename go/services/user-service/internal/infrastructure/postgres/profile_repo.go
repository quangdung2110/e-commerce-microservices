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

var _ port.ProfileRepository = (*ProfileRepository)(nil)

// ProfileRepository persists "user".user_profiles.
type ProfileRepository struct {
	repository
}

// NewProfileRepository wires the profile repository.
func NewProfileRepository(pool *pgxpool.Pool) *ProfileRepository {
	return &ProfileRepository{repository{pool: pool}}
}

const profileColumns = `id, first_name, last_name, date_of_birth, gender, created_at, updated_at`

// Insert writes the bootstrap row. ON CONFLICT DO NOTHING makes a redelivered
// USER_VERIFIED_SUCCESS a no-op instead of an overwrite (USER-BR-005).
func (r *ProfileRepository) Insert(ctx context.Context, profile *entity.Profile) (bool, error) {
	const query = `
		INSERT INTO "user".user_profiles (id, first_name, last_name, date_of_birth, gender, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (id) DO NOTHING`

	tag, err := r.db(ctx).Exec(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.DateOfBirth,
		profile.Gender.String(),
		profile.CreatedAt,
		profile.UpdatedAt,
	)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// GetByID reads one profile.
func (r *ProfileRepository) GetByID(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	const query = `SELECT ` + profileColumns + ` FROM "user".user_profiles WHERE id = $1`
	return r.queryProfile(ctx, query, userID)
}

// GetForUpdate reads and locks the row for the rest of the transaction, so a
// profile update and an incoming anonymization serialize (WF-USER-002).
func (r *ProfileRepository) GetForUpdate(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	const query = `SELECT ` + profileColumns + ` FROM "user".user_profiles WHERE id = $1 FOR UPDATE`
	return r.queryProfile(ctx, query, userID)
}

// GetActive reads a profile that has no retention record. The NOT EXISTS keeps a
// deleted account indistinguishable from a purged one for every owner-facing
// path (docs/user/domain.md §8).
func (r *ProfileRepository) GetActive(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	const query = `
		SELECT ` + profileColumns + `
		FROM "user".user_profiles
		WHERE id = $1
		  AND NOT EXISTS (SELECT 1 FROM "user".user_deletions WHERE user_id = $1)`
	return r.queryProfile(ctx, query, userID)
}

// GetActiveForUpdate locks the row first and checks the retention record in a
// second statement. Under READ COMMITTED that statement takes a new snapshot, so
// when an anonymization was holding the lock it already sees the record that
// transaction committed. Folding the check into the FOR UPDATE query would not
// guarantee that, because the subquery would read the pre-lock snapshot.
func (r *ProfileRepository) GetActiveForUpdate(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	profile, err := r.GetForUpdate(ctx, userID)
	if err != nil {
		return nil, err
	}

	const query = `SELECT EXISTS (SELECT 1 FROM "user".user_deletions WHERE user_id = $1)`
	var deleted bool
	if err := r.db(ctx).QueryRow(ctx, query, userID).Scan(&deleted); err != nil {
		return nil, mapError(err)
	}
	if deleted {
		return nil, apperr.ErrProfileNotFound
	}
	return profile, nil
}

// Update persists the mutable columns. The stored `updated_at` is read back,
// because the schema trigger owns that value and the published event has to
// carry exactly what the database holds (ADR-007).
func (r *ProfileRepository) Update(ctx context.Context, profile *entity.Profile) error {
	const query = `
		UPDATE "user".user_profiles
		SET first_name = $2,
		    last_name = $3,
		    date_of_birth = $4,
		    gender = $5
		WHERE id = $1
		RETURNING updated_at`

	var updatedAt time.Time
	err := r.db(ctx).QueryRow(ctx, query,
		profile.ID,
		profile.FirstName,
		profile.LastName,
		profile.DateOfBirth,
		profile.Gender.String(),
	).Scan(&updatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return apperr.ErrProfileNotFound
		}
		return mapError(err)
	}

	profile.UpdatedAt = updatedAt.UTC()
	return nil
}

// HardDelete removes the profile once its retention window elapsed. Addresses
// and preferences cascade, which is only a safety net: the deletion consumer
// removed them at anonymization time (WF-USER-007).
func (r *ProfileRepository) HardDelete(ctx context.Context, userID uuid.UUID) (bool, error) {
	const query = `DELETE FROM "user".user_profiles WHERE id = $1`

	tag, err := r.db(ctx).Exec(ctx, query, userID)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

func (r *ProfileRepository) queryProfile(ctx context.Context, query string, userID uuid.UUID) (*entity.Profile, error) {
	var (
		profile     entity.Profile
		dateOfBirth *time.Time
		gender      string
	)
	err := r.db(ctx).QueryRow(ctx, query, userID).Scan(
		&profile.ID,
		&profile.FirstName,
		&profile.LastName,
		&dateOfBirth,
		&gender,
		&profile.CreatedAt,
		&profile.UpdatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperr.ErrProfileNotFound
		}
		return nil, mapError(err)
	}

	parsedGender, err := vo.ParseGender(gender)
	if err != nil {
		// The CHECK constraint should make this unreachable; a stored value
		// outside the enum is a schema defect, not client input.
		return nil, apperr.ErrInternal.WithCause(err)
	}
	profile.Gender = parsedGender
	if dateOfBirth != nil {
		day := dateOfBirth.UTC()
		profile.DateOfBirth = &day
	}
	profile.CreatedAt = profile.CreatedAt.UTC()
	profile.UpdatedAt = profile.UpdatedAt.UTC()
	return &profile, nil
}

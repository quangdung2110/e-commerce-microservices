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

var _ port.AddressRepository = (*AddressRepository)(nil)

// AddressRepository persists "user".user_addresses.
type AddressRepository struct {
	repository
}

// NewAddressRepository wires the address repository.
func NewAddressRepository(pool *pgxpool.Pool) *AddressRepository {
	return &AddressRepository{repository{pool: pool}}
}

const addressColumns = `id, user_id, is_default, recipient_name, recipient_phone,
	address_line, province_code, district_code, ward_code, created_at, updated_at`

// LockUser takes a transaction-scoped advisory lock on the address book of one
// user.
//
// A row lock cannot serialize the case that matters here: two requests creating
// the very first address both see an empty book, and rows that do not exist yet
// cannot be locked. The advisory lock covers the whole address book, including
// the empty one, and is released when the transaction ends
// (docs/user/data.md §5).
func (r *AddressRepository) LockUser(ctx context.Context, userID uuid.UUID) error {
	const query = `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`

	if _, err := r.db(ctx).Exec(ctx, query, addressLockScope+userID.String()); err != nil {
		return mapError(err)
	}
	return nil
}

// addressLockScope namespaces the advisory lock so it cannot collide with a
// lock another feature might take on the same user id.
const addressLockScope = "user:addresses:"

// ListByUser returns the address book ordered default first, then newest first,
// which is the order checkout expects and the order the cache stores.
func (r *AddressRepository) ListByUser(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	const query = `
		SELECT ` + addressColumns + `
		FROM "user".user_addresses
		WHERE user_id = $1
		ORDER BY is_default DESC, created_at DESC`

	rows, err := r.db(ctx).Query(ctx, query, userID)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	addresses := make([]*entity.Address, 0, 4)
	for rows.Next() {
		address, err := scanAddress(rows)
		if err != nil {
			return nil, mapError(err)
		}
		addresses = append(addresses, address)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return addresses, nil
}

// GetOwned looks an address up by owner and id together. An address of another
// user is reported as missing, so the API never discloses that it exists
// (USER-BR-001).
func (r *AddressRepository) GetOwned(ctx context.Context, userID, addressID uuid.UUID) (*entity.Address, error) {
	const query = `
		SELECT ` + addressColumns + `
		FROM "user".user_addresses
		WHERE id = $1 AND user_id = $2`

	rows, err := r.db(ctx).Query(ctx, query, addressID, userID)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return nil, mapError(err)
		}
		return nil, apperr.ErrAddressNotFound
	}

	address, err := scanAddress(rows)
	if err != nil {
		return nil, mapError(err)
	}
	return address, nil
}

// CountByUser reports how many addresses the profile holds.
func (r *AddressRepository) CountByUser(ctx context.Context, userID uuid.UUID) (int, error) {
	const query = `SELECT count(*) FROM "user".user_addresses WHERE user_id = $1`

	var count int
	if err := r.db(ctx).QueryRow(ctx, query, userID).Scan(&count); err != nil {
		return 0, mapError(err)
	}
	return count, nil
}

// Insert writes a new address.
func (r *AddressRepository) Insert(ctx context.Context, address *entity.Address) error {
	const query = `
		INSERT INTO "user".user_addresses (
			id, user_id, is_default, recipient_name, recipient_phone,
			address_line, province_code, district_code, ward_code, created_at, updated_at
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`

	_, err := r.db(ctx).Exec(ctx, query,
		address.ID,
		address.UserID,
		address.IsDefault,
		address.RecipientName,
		address.RecipientPhone.String(),
		address.AddressLine,
		address.ProvinceCode,
		address.DistrictCode,
		address.WardCode,
		address.CreatedAt,
		address.UpdatedAt,
	)
	if err != nil {
		return mapError(err)
	}
	return nil
}

// Update replaces the mutable columns of one address. `is_default` is not among
// them: it changes only through SetDefault and ClearDefault.
func (r *AddressRepository) Update(ctx context.Context, address *entity.Address) error {
	const query = `
		UPDATE "user".user_addresses
		SET recipient_name = $3,
		    recipient_phone = $4,
		    address_line = $5,
		    province_code = $6,
		    district_code = $7,
		    ward_code = $8
		WHERE id = $1 AND user_id = $2
		RETURNING updated_at`

	var updatedAt time.Time
	err := r.db(ctx).QueryRow(ctx, query,
		address.ID,
		address.UserID,
		address.RecipientName,
		address.RecipientPhone.String(),
		address.AddressLine,
		address.ProvinceCode,
		address.DistrictCode,
		address.WardCode,
	).Scan(&updatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return apperr.ErrAddressNotFound
		}
		return mapError(err)
	}

	address.UpdatedAt = updatedAt.UTC()
	return nil
}

// Delete hard-deletes one address of one owner. Orders keep their own snapshot,
// so nothing downstream is affected (USER-INV-004).
func (r *AddressRepository) Delete(ctx context.Context, userID, addressID uuid.UUID) error {
	const query = `DELETE FROM "user".user_addresses WHERE id = $1 AND user_id = $2`

	tag, err := r.db(ctx).Exec(ctx, query, addressID, userID)
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		return apperr.ErrAddressNotFound
	}
	return nil
}

// ClearDefault removes the flag from every other address of the owner. It runs
// before SetDefault inside the same transaction, so the partial unique index is
// never violated in between (USER-BR-003).
func (r *AddressRepository) ClearDefault(ctx context.Context, userID, exceptAddressID uuid.UUID) error {
	const query = `
		UPDATE "user".user_addresses
		SET is_default = FALSE
		WHERE user_id = $1 AND id <> $2 AND is_default`

	if _, err := r.db(ctx).Exec(ctx, query, userID, exceptAddressID); err != nil {
		return mapError(err)
	}
	return nil
}

// SetDefault marks one address as the default of its owner and reports the
// `updated_at` the schema trigger stored.
func (r *AddressRepository) SetDefault(ctx context.Context, userID, addressID uuid.UUID) (time.Time, error) {
	const query = `
		UPDATE "user".user_addresses
		SET is_default = TRUE
		WHERE id = $1 AND user_id = $2
		RETURNING updated_at`

	var updatedAt time.Time
	err := r.db(ctx).QueryRow(ctx, query, addressID, userID).Scan(&updatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return time.Time{}, apperr.ErrAddressNotFound
		}
		return time.Time{}, mapError(err)
	}
	return updatedAt.UTC(), nil
}

// DeleteAllForUser empties the address book of an anonymized account.
func (r *AddressRepository) DeleteAllForUser(ctx context.Context, userID uuid.UUID) (int64, error) {
	const query = `DELETE FROM "user".user_addresses WHERE user_id = $1`

	tag, err := r.db(ctx).Exec(ctx, query, userID)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func scanAddress(rows pgx.Rows) (*entity.Address, error) {
	var (
		address entity.Address
		phone   string
	)
	err := rows.Scan(
		&address.ID,
		&address.UserID,
		&address.IsDefault,
		&address.RecipientName,
		&phone,
		&address.AddressLine,
		&address.ProvinceCode,
		&address.DistrictCode,
		&address.WardCode,
		&address.CreatedAt,
		&address.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}

	// The CHECK constraint already guarantees the E.164 format of a stored
	// number, so it is taken as-is rather than revalidated on every read.
	address.RecipientPhone = vo.MustPhone(phone)
	address.CreatedAt = address.CreatedAt.UTC()
	address.UpdatedAt = address.UpdatedAt.UTC()
	return &address, nil
}

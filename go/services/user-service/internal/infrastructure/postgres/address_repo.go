package postgres

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"user-service/internal/domain/entity"
)

// PostgresAddressRepository implements port.AddressRepository
type PostgresAddressRepository struct {
	db *pgxpool.Pool
}

// NewPostgresAddressRepository creates a new PostgresAddressRepository
func NewPostgresAddressRepository(db *pgxpool.Pool) *PostgresAddressRepository {
	return &PostgresAddressRepository{db: db}
}

// Create creates a new address
func (r *PostgresAddressRepository) Create(ctx context.Context, addr *entity.Address) error {
	query := `
		INSERT INTO user_addresses (id, user_id, is_default, recipient_name, phone_number, address_line, ward_id, district_id, city_id, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW(), NOW())
		RETURNING created_at, updated_at`

	err := r.db.QueryRow(ctx, query,
		addr.ID,
		addr.UserID,
		addr.IsDefault,
		addr.RecipientName,
		addr.PhoneNumber,
		addr.AddressLine,
		addr.WardID,
		addr.DistrictID,
		addr.CityID,
	).Scan(&addr.CreatedAt, &addr.UpdatedAt)

	if err != nil {
		return fmt.Errorf("failed to create address: %w", err)
	}

	return nil
}

// CreateTx creates a new address within a transaction
func (r *PostgresAddressRepository) CreateTx(ctx context.Context, tx pgx.Tx, addr *entity.Address) error {
	query := `
		INSERT INTO user_addresses (id, user_id, is_default, recipient_name, phone_number, address_line, ward_id, district_id, city_id, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW(), NOW())
		RETURNING created_at, updated_at`

	err := tx.QueryRow(ctx, query,
		addr.ID,
		addr.UserID,
		addr.IsDefault,
		addr.RecipientName,
		addr.PhoneNumber,
		addr.AddressLine,
		addr.WardID,
		addr.DistrictID,
		addr.CityID,
	).Scan(&addr.CreatedAt, &addr.UpdatedAt)

	if err != nil {
		return fmt.Errorf("failed to create address in transaction: %w", err)
	}

	return nil
}

// GetByID retrieves an address by its ID
func (r *PostgresAddressRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Address, error) {
	query := `
		SELECT id, user_id, is_default, recipient_name, phone_number, address_line, ward_id, district_id, city_id, created_at, updated_at
		FROM user_addresses
		WHERE id = $1`

	var addr entity.Address
	err := r.db.QueryRow(ctx, query, id).Scan(
		&addr.ID,
		&addr.UserID,
		&addr.IsDefault,
		&addr.RecipientName,
		&addr.PhoneNumber,
		&addr.AddressLine,
		&addr.WardID,
		&addr.DistrictID,
		&addr.CityID,
		&addr.CreatedAt,
		&addr.UpdatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get address: %w", err)
	}

	return &addr, nil
}

// GetByUserID retrieves all addresses for a user
func (r *PostgresAddressRepository) GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	query := `
		SELECT id, user_id, is_default, recipient_name, phone_number, address_line, ward_id, district_id, city_id, created_at, updated_at
		FROM user_addresses
		WHERE user_id = $1
		ORDER BY is_default DESC, created_at DESC`

	rows, err := r.db.Query(ctx, query, userID)
	if err != nil {
		return nil, fmt.Errorf("failed to get addresses: %w", err)
	}
	defer rows.Close()

	var addresses []*entity.Address
	for rows.Next() {
		var addr entity.Address
		err := rows.Scan(
			&addr.ID,
			&addr.UserID,
			&addr.IsDefault,
			&addr.RecipientName,
			&addr.PhoneNumber,
			&addr.AddressLine,
			&addr.WardID,
			&addr.DistrictID,
			&addr.CityID,
			&addr.CreatedAt,
			&addr.UpdatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan address: %w", err)
		}
		addresses = append(addresses, &addr)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}

	return addresses, nil
}

// Update updates an existing address
func (r *PostgresAddressRepository) Update(ctx context.Context, addr *entity.Address) error {
	query := `
		UPDATE user_addresses
		SET recipient_name = $2,
		    phone_number = $3,
		    address_line = $4,
		    ward_id = $5,
		    district_id = $6,
		    city_id = $7,
		    updated_at = NOW()
		WHERE id = $1
		RETURNING updated_at`

	err := r.db.QueryRow(ctx, query,
		addr.ID,
		addr.RecipientName,
		addr.PhoneNumber,
		addr.AddressLine,
		addr.WardID,
		addr.DistrictID,
		addr.CityID,
	).Scan(&addr.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update address: %w", err)
	}

	return nil
}

// UpdateTx updates an address within a transaction
func (r *PostgresAddressRepository) UpdateTx(ctx context.Context, tx pgx.Tx, addr *entity.Address) error {
	query := `
		UPDATE user_addresses
		SET recipient_name = $2,
		    phone_number = $3,
		    address_line = $4,
		    ward_id = $5,
		    district_id = $6,
		    city_id = $7,
		    updated_at = NOW()
		WHERE id = $1
		RETURNING updated_at`

	err := tx.QueryRow(ctx, query,
		addr.ID,
		addr.RecipientName,
		addr.PhoneNumber,
		addr.AddressLine,
		addr.WardID,
		addr.DistrictID,
		addr.CityID,
	).Scan(&addr.UpdatedAt)

	if err == pgx.ErrNoRows {
		return sql.ErrNoRows
	}
	if err != nil {
		return fmt.Errorf("failed to update address in transaction: %w", err)
	}

	return nil
}

// Delete hard deletes an address
func (r *PostgresAddressRepository) Delete(ctx context.Context, id uuid.UUID) error {
	query := `DELETE FROM user_addresses WHERE id = $1`

	result, err := r.db.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete address: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// DeleteTx hard deletes an address within a transaction
func (r *PostgresAddressRepository) DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	query := `DELETE FROM user_addresses WHERE id = $1`

	result, err := tx.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete address in transaction: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// DeleteByUserID deletes all addresses for a user (used in GDPR deletion)
func (r *PostgresAddressRepository) DeleteByUserID(ctx context.Context, userID uuid.UUID) error {
	query := `DELETE FROM user_addresses WHERE user_id = $1`

	_, err := r.db.Exec(ctx, query, userID)
	if err != nil {
		return fmt.Errorf("failed to delete addresses by user: %w", err)
	}

	return nil
}

// DeleteByUserIDTx deletes all addresses for a user within a transaction
func (r *PostgresAddressRepository) DeleteByUserIDTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	query := `DELETE FROM user_addresses WHERE user_id = $1`

	_, err := tx.Exec(ctx, query, userID)
	if err != nil {
		return fmt.Errorf("failed to delete addresses by user in transaction: %w", err)
	}

	return nil
}

// SetDefault sets an address as default for a user (removes default from others first)
func (r *PostgresAddressRepository) SetDefault(ctx context.Context, userID, addrID uuid.UUID) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback(ctx)

	if err := r.SetDefaultTx(ctx, tx, userID, addrID); err != nil {
		return err
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	return nil
}

// SetDefaultTx sets an address as default within a transaction
func (r *PostgresAddressRepository) SetDefaultTx(ctx context.Context, tx pgx.Tx, userID, addrID uuid.UUID) error {
	// First, remove default from all other addresses
	unsetQuery := `UPDATE user_addresses SET is_default = FALSE, updated_at = NOW() WHERE user_id = $1 AND is_default = TRUE`
	_, err := tx.Exec(ctx, unsetQuery, userID)
	if err != nil {
		return fmt.Errorf("failed to unset default address: %w", err)
	}

	// Then, set the new default
	setQuery := `UPDATE user_addresses SET is_default = TRUE, updated_at = NOW() WHERE id = $1 AND user_id = $2`
	result, err := tx.Exec(ctx, setQuery, addrID, userID)
	if err != nil {
		return fmt.Errorf("failed to set default address: %w", err)
	}

	if result.RowsAffected() == 0 {
		return sql.ErrNoRows
	}

	return nil
}

// PromoteOldestToDefault promotes the oldest remaining address to default
// Returns the ID of the promoted address, or nil if no addresses remain
func (r *PostgresAddressRepository) PromoteOldestToDefault(ctx context.Context, tx pgx.Tx, userID uuid.UUID, excludeAddrID uuid.UUID) (*uuid.UUID, error) {
	// Find the oldest address excluding the one being deleted
	query := `
		UPDATE user_addresses
		SET is_default = TRUE, updated_at = NOW()
		WHERE id = (
			SELECT id FROM user_addresses
			WHERE user_id = $1 AND id != $2
			ORDER BY created_at ASC
			LIMIT 1
		)
		RETURNING id`

	var promotedID uuid.UUID
	err := tx.QueryRow(ctx, query, userID, excludeAddrID).Scan(&promotedID)

	if err == pgx.ErrNoRows {
		// No other addresses to promote
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to promote oldest address: %w", err)
	}

	return &promotedID, nil
}

// CountByUserID counts addresses for a user
func (r *PostgresAddressRepository) CountByUserID(ctx context.Context, userID uuid.UUID) (int, error) {
	query := `SELECT COUNT(*) FROM user_addresses WHERE user_id = $1`

	var count int
	err := r.db.QueryRow(ctx, query, userID).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count addresses: %w", err)
	}

	return count, nil
}

// HasOtherAddresses checks if user has other addresses besides the given one
func (r *PostgresAddressRepository) HasOtherAddresses(ctx context.Context, userID uuid.UUID, excludeAddrID uuid.UUID) (bool, error) {
	query := `SELECT EXISTS(SELECT 1 FROM user_addresses WHERE user_id = $1 AND id != $2)`

	var exists bool
	err := r.db.QueryRow(ctx, query, userID, excludeAddrID).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("failed to check other addresses: %w", err)
	}

	return exists, nil
}

// GetDefaultAddress retrieves the default address for a user
func (r *PostgresAddressRepository) GetDefaultAddress(ctx context.Context, userID uuid.UUID) (*entity.Address, error) {
	query := `
		SELECT id, user_id, is_default, recipient_name, phone_number, address_line, ward_id, district_id, city_id, created_at, updated_at
		FROM user_addresses
		WHERE user_id = $1 AND is_default = TRUE
		LIMIT 1`

	var addr entity.Address
	err := r.db.QueryRow(ctx, query, userID).Scan(
		&addr.ID,
		&addr.UserID,
		&addr.IsDefault,
		&addr.RecipientName,
		&addr.PhoneNumber,
		&addr.AddressLine,
		&addr.WardID,
		&addr.DistrictID,
		&addr.CityID,
		&addr.CreatedAt,
		&addr.UpdatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get default address: %w", err)
	}

	return &addr, nil
}

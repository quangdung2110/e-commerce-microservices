package port

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"user-service/internal/domain/entity"
)

// AddressRepository defines the interface for address data access
type AddressRepository interface {
	// Create creates a new address
	Create(ctx context.Context, addr *entity.Address) error

	// CreateTx creates a new address within a transaction
	CreateTx(ctx context.Context, tx pgx.Tx, addr *entity.Address) error

	// GetByID retrieves an address by its ID
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Address, error)

	// GetByUserID retrieves all addresses for a user
	GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error)

	// Update updates an existing address
	Update(ctx context.Context, addr *entity.Address) error

	// UpdateTx updates an address within a transaction
	UpdateTx(ctx context.Context, tx pgx.Tx, addr *entity.Address) error

	// Delete hard deletes an address
	Delete(ctx context.Context, id uuid.UUID) error

	// DeleteTx hard deletes an address within a transaction
	DeleteTx(ctx context.Context, tx pgx.Tx, id uuid.UUID) error

	// DeleteByUserID deletes all addresses for a user (used in GDPR deletion)
	DeleteByUserID(ctx context.Context, userID uuid.UUID) error

	// DeleteByUserIDTx deletes all addresses for a user within a transaction
	DeleteByUserIDTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error

	// SetDefault sets an address as default for a user (removes default from others first)
	SetDefault(ctx context.Context, userID, addrID uuid.UUID) error

	// SetDefaultTx sets an address as default within a transaction
	SetDefaultTx(ctx context.Context, tx pgx.Tx, userID, addrID uuid.UUID) error

	// PromoteOldestToDefault promotes the oldest remaining address to default
	// Returns the ID of the promoted address, or nil if no addresses remain
	PromoteOldestToDefault(ctx context.Context, tx pgx.Tx, userID uuid.UUID, excludeAddrID uuid.UUID) (*uuid.UUID, error)

	// CountByUserID counts addresses for a user
	CountByUserID(ctx context.Context, userID uuid.UUID) (int, error)

	// HasOtherAddresses checks if user has other addresses besides the given one
	HasOtherAddresses(ctx context.Context, userID uuid.UUID, excludeAddrID uuid.UUID) (bool, error)
}

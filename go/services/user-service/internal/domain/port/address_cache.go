package port

import (
	"context"

	"github.com/google/uuid"

	"user-service/internal/domain/entity"
)

// AddressCache defines the interface for address caching
type AddressCache interface {
	// Get retrieves cached addresses for a user
	// Returns nil if cache miss (caller should query DB)
	Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error)

	// Set caches addresses for a user with appropriate TTL
	// Empty arrays get shorter TTL to prevent cache penetration
	Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error

	// Delete removes cached addresses for a user
	Delete(ctx context.Context, userID uuid.UUID) error
}

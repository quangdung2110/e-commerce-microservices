package port

import "context"

// CacheRepository defines simple Redis cache operations.
// These are simple operations that do not require Lua scripts.
type CacheRepository interface {
	// GetCart reads all fields from a cart hash.
	// Returns map[skuID]JSONValue or empty map if key doesn't exist.
	// Does NOT emit events.
	GetCart(ctx context.Context, cartKey string) (map[string]string, error)

	// DeleteNegativeCache removes the negative cache key for a cart.
	// Called after successful Add/Update/Delete to prevent stale empty responses.
	DeleteNegativeCache(ctx context.Context, userID string) error

	// SetNegativeCache sets a negative cache marker (key not found indicator).
	// TTL: 60 seconds. Called when cart not in Redis AND not in PostgreSQL.
	SetNegativeCache(ctx context.Context, userID string) error

	// HasNegativeCache checks if a negative cache key exists.
	// Returns true if cart was recently confirmed as non-existent.
	HasNegativeCache(ctx context.Context, userID string) (bool, error)

	// SetItemAvailability updates only the is_available field of a cart item.
	// Called when gRPC returns product BANNED/UNAVAILABLE.
	// This is the ONLY exception to the "read-only TTL" rule.
	// Does NOT call EXPIRE — only updates the field.
	SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error

	// Ping checks Redis connectivity.
	Ping(ctx context.Context) error
}

package port

import (
	"context"
	"time"

	"cart-service/internal/domain/entity"
)

// CartRepository defines PostgreSQL cold store operations.
// Only used for:
//   1. Cache-miss rehydrate (read from PG to Redis)
//   2. Postgres Sync Worker (write from Kafka events to PG)
//   3. Cronjobs (Abandoned Marker, Archival Cleaner)
type CartRepository interface {
	// GetCartStatus returns the status of a cart from cold storage.
	// Used in cache-miss rehydrate to determine if cart is active.
	GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error)

	// ListCartsForAbandonedMark returns carts that are ACTIVE
	// and have not been updated for inactiveDays.
	// Used by Abandoned Cart Marker cronjob.
	// Batch size should be respected (LIMIT).
	ListCartsForAbandonedMark(ctx context.Context, inactiveDays int, limit int) ([]*entity.Cart, error)

	// UpdateCartStatus updates cart status with stream_id guard.
	// Only updates if newStreamID > current last_event_stream_id.
	// Returns rowsAffected.
	UpdateCartStatus(ctx context.Context, cartID string, status entity.CartStatus, streamID string) (int64, error)

	// UpsertCartItem inserts or updates a cart item with stream_id guard.
	// Implements ON CONFLICT DO UPDATE WHERE redis_stream_id < newStreamID.
	// Also upserts the cart master row.
	// Used by Postgres Sync Worker.
	UpsertCartItem(ctx context.Context, item *entity.CartItem, streamID string) error

	// BatchUpsertCartItems performs batch upsert for multiple items of the same cart.
	// Within one database transaction per cart_id.
	BatchUpsertCartItems(ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string) error

	// DeleteCartItem deletes a specific item with stream_id guard.
	// Hard delete (not soft delete). Only deletes if redis_stream_id < eventStreamID.
	DeleteCartItem(ctx context.Context, itemID string, streamID string) (int64, error)

	// BatchDeleteCartItems performs hard delete for multiple items.
	// Used by Postgres Sync Worker.
	BatchDeleteCartItems(ctx context.Context, itemIDs []string, streamID string) (int64, error)

	// UpsertCartMaster inserts or updates the cart master record.
	// Used by Postgres Sync Worker for ADD/UPDATE/CLEARED/CHECKED_OUT events.
	UpsertCartMaster(ctx context.Context, cart *entity.Cart, streamID string) error

	// ListCartsForCleanup returns carts eligible for hard delete.
	// Used by Data Archival Cleaner.
	ListCartsForCleanup(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int) ([]string, error)

	// HardDeleteCart permanently removes a cart and all its items.
	// Must be wrapped in a transaction: DELETE cart_items FIRST, then DELETE carts.
	HardDeleteCart(ctx context.Context, cartID string) error

	// ListPendingAbandonedEvents returns carts marked ABANDONED but not yet published.
	// Used for recovery if XADD failed after DB update.
	ListPendingAbandonedEvents(ctx context.Context, olderThan time.Time) ([]*entity.Cart, error)

	// MarkAbandonedEventPublished updates abandoned_event_published_at.
	MarkAbandonedEventPublished(ctx context.Context, cartID string) error

	// GetCartItems retrieves all cart items for a given cart ID from cold storage.
	// Used during cache-miss rehydration.
	GetCartItems(ctx context.Context, cartID string) ([]*entity.CartItem, error)

	// UpsertCartItemWithPayload inserts or updates a cart item using raw payload map.
	// Used by Postgres Sync Worker when processing Kafka events with raw JSON payload.
	UpsertCartItemWithPayload(ctx context.Context, cartID string, payload map[string]interface{}, streamID string) error

	// UpsertCartMasterWithStatus inserts or updates a cart master record by userID and status.
	// Used by Postgres Sync Worker for ADD/UPDATE/CLEARED/CHECKED_OUT events via Kafka payload.
	UpsertCartMasterWithStatus(ctx context.Context, userID string, status entity.CartStatus, streamID string, updatedAt time.Time) error
}

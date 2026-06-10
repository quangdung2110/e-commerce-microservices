package port

import "context"

// CartLuaRepository defines Redis Lua Script operations for cart.
// Ref: cart_caching_schema.md §3
//
// All write operations must go through this interface to ensure:
//   - Atomic quantity accumulation (no race)
//   - Cart capacity check (HLEN < max_skus)
//   - Per-item quantity cap (new_qty <= max_qty)
//   - HSET + XADD in same atomic context
//   - TTL refresh (EXPIRE 7 days)
type CartLuaRepository interface {
	// AddOrUpdateItem atomically adds or updates a cart item.
	// Executes the add_or_update Lua script.
	// Returns the encoded JSON of the item (from Lua return) and any error.
	//
	// Parameters:
	//   - cartKey: Redis key with Hash Tag, e.g. "{cart}:{user_id}"
	//   - streamShard: Global stream shard, e.g. "{cart}:events:0"
	//   - skuID: SKU identifier
	//   - itemJSON: Full JSON payload built by caller
	//   - maxQty: Max quantity per SKU (e.g. 100)
	//   - maxSkus: Max SKUs per cart (e.g. 50)
	//   - step: Quantity delta (+1, -1, etc.)
	//   - eventTimeMs: Epoch milliseconds for business timestamp
	//   - userID: User ID (needed for event payload)
	//
	// Returns:
	//   - encoded item JSON from Lua
	//   - error: ErrCartFull, ErrQuantityInvalid, ErrCannotDecrementNonExisting
	//
	// Lua script guarantees:
	//   - Atomic quantity accumulation (no race)
	//   - Cart capacity check (HLEN < max_skus)
	//   - Per-item quantity cap (new_qty <= max_qty)
	//   - HSET + XADD in same atomic context
	//   - TTL refresh (EXPIRE 7 days)
	AddOrUpdateItem(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error)

	// BatchDelete removes multiple SKUs from a cart atomically.
	// Executes the batch_delete Lua script.
	//
	// Parameters:
	//   - cartKey: Redis key with Hash Tag, e.g. "{cart}:{user_id}"
	//   - streamShard: Global stream shard, e.g. "{cart}:events:0"
	//   - timestampMs: Current epoch milliseconds
	//   - eventType: "CART_CLEARED" or "CART_CHECKED_OUT"
	//   - userID: User ID (needed for event payload)
	//   - skuIDsJSON: JSON array of SKU IDs to delete
	//
	// Returns:
	//   - number of items deleted
	//   - error
	//
	// Lua script guarantees:
	//   - HDEL only existing SKUs
	//   - Emits CART_ITEM_DELETED per deleted SKU
	//   - Emits CART_CLEARED or CART_CHECKED_OUT if cart becomes empty
	//   - DEL cart key if HLEN == 0
	BatchDelete(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error)

	// Rehydrate populates a cart from cold storage without emitting events.
	// Executes the silent rehydrate Lua script.
	//
	// Parameters:
	//   - cartKey: Redis key with Hash Tag
	//   - itemsJSON: JSON array of {sku_id, value} objects already merged with product data
	//   - ttlSeconds: TTL to set (default 604800)
	//
	// Returns:
	//   - error
	//
	// Lua script guarantees:
	//   - NO XADD (silent)
	//   - HSET only if field missing OR incoming.updated_at > redis.updated_at
	//   - EXPIRE at end
	Rehydrate(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error
}

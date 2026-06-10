package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"cart-service/internal/domain/port"

	"github.com/redis/go-redis/v9"
)

// CartLuaRepo implements port.CartLuaRepository using Redis Lua scripts.
// It is the primary write path for cart mutations.
//
// All mutations go through Lua scripts to guarantee:
//   - Atomic quantity accumulation (no read-modify-write races)
//   - Cart capacity enforcement (HLEN < maxSKUs)
//   - Per-item quantity cap (newQty <= maxQty)
//   - HSET + XADD in same atomic Lua transaction
//   - TTL refresh (EXPIRE)
type CartLuaRepo struct {
	client *Client
	sm     *ShardManager
}

// NewCartLuaRepo creates a CartLuaRepo with the given Redis client and shard manager.
func NewCartLuaRepo(client *Client, sm *ShardManager) *CartLuaRepo {
	return &CartLuaRepo{
		client: client,
		sm:     sm,
	}
}

// Compile-time interface assertion
var _ port.CartLuaRepository = (*CartLuaRepo)(nil)

// AddOrUpdateItem implements port.CartLuaRepository.
// It executes the add_or_update.lua script atomically.
//
// Parameters:
//   - cartKey: Redis key with Hash Tag, e.g. "{cart}:{user_id}"
//   - streamShard: Global stream shard, e.g. "{cart}:events:0"
//   - skuID: SKU identifier
//   - itemJSON: Full JSON payload built by caller (must include quantity from step calculation)
//   - maxQty: Max quantity per SKU (e.g. 100)
//   - maxSkus: Max SKUs per cart (e.g. 50)
//   - step: Quantity delta (+1, -1, etc.)
//   - eventTimeMs: Epoch milliseconds for business timestamp
//   - userID: User ID (needed for event payload)
//
// Returns:
//   - encoded item JSON from Lua (with final quantity)
//   - error: port.ErrCartFull, port.ErrQuantityInvalid, port.ErrCannotDecrementNonExisting, or wrapped Redis errors
//
// Lua script guarantees atomic quantity accumulation, cart capacity check,
// per-item quantity cap, HSET+XADD in same atomic context, and TTL refresh.
func (r *CartLuaRepo) AddOrUpdateItem(
	ctx context.Context,
	cartKey, streamShard, skuID, itemJSON string,
	maxQty, maxSkus, step int,
	eventTimeMs int64,
	userID string,
) (string, error) {
	result, err := r.client.Raw().Eval(ctx, AddOrUpdateScript,
		[]string{cartKey, streamShard}, // KEYS[1], KEYS[2]
		skuID,         // ARGV[1] — sku_id
		itemJSON,      // ARGV[2] — item JSON (full item data, Lua may update quantity)
		maxQty,        // ARGV[3] — max quantity per SKU
		maxSkus,       // ARGV[4] — max SKUs per cart
		step,          // ARGV[5] — quantity delta
		eventTimeMs,   // ARGV[6] — business timestamp (epoch ms string)
		userID,        // ARGV[7] — user_id (for event payload)
	).Result()
	if err != nil {
		if err == redis.Nil {
			return "", fmt.Errorf("lua script returned nil unexpectedly")
		}
		return "", fmt.Errorf("%w: %v", port.ErrLuaScriptFailed, err)
	}

	// Result is either:
	//   - A JSON string (success): '{"product_id":"...","quantity":5,...}'
	//   - A JSON error object (failure): '{"err":"Cart Full"}'
	resultStr, ok := result.(string)
	if !ok {
		return "", fmt.Errorf("unexpected lua script return type %T: %v", result, result)
	}

	// Check if it's an error object
	var errResp struct {
		Err string `json:"err"`
	}
	if json.Unmarshal([]byte(resultStr), &errResp) == nil && errResp.Err != "" {
		return "", mapLuaError(errResp.Err)
	}

	return resultStr, nil
}

// BatchDelete implements port.CartLuaRepository.
// It executes the batch_delete.lua script to remove multiple SKUs atomically.
//
// Parameters:
//   - cartKey: Redis hash key, e.g. "{cart}:{user_id}"
//   - streamShard: Event stream shard, e.g. "{cart}:events:0"
//   - timestampMs: Current epoch milliseconds
//   - eventType: "CART_CLEARED" or "CART_CHECKED_OUT"
//   - userID: User ID (for event payload)
//   - skuIDsJSON: JSON array of SKU IDs to delete, e.g. '["SKU001","SKU002"]'
//
// Returns:
//   - number of items deleted
//   - error: nil on success
//
// Lua script guarantees HDEL only existing SKUs, one CART_ITEM_DELETED per SKU,
// CART_CLEARED/CART_CHECKED_OUT when HLEN reaches 0, and DEL key if empty.
func (r *CartLuaRepo) BatchDelete(
	ctx context.Context,
	cartKey, streamShard string,
	timestampMs int64,
	eventType, userID, skuIDsJSON string,
) (int, error) {
	result, err := r.client.Raw().Eval(ctx, BatchDeleteScript,
		[]string{cartKey, streamShard}, // KEYS[1], KEYS[2]
		timestampMs,  // ARGV[1] — current timestamp (epoch ms string)
		eventType,    // ARGV[2] — "CART_CLEARED" or "CART_CHECKED_OUT"
		userID,       // ARGV[3] — user_id
		skuIDsJSON,   // ARGV[4] — JSON array of SKU IDs
	).Result()
	if err != nil {
		return 0, fmt.Errorf("%w: %v", port.ErrLuaScriptFailed, err)
	}

	// Check for error object first
	resultStr, ok := result.(string)
	if ok {
		var errResp struct {
			Err string `json:"err"`
		}
		if json.Unmarshal([]byte(resultStr), &errResp) == nil && errResp.Err != "" {
			return 0, mapLuaError(errResp.Err)
		}
	}

	// Result should be an integer (deleted count)
	deleted, ok := result.(int64)
	if !ok {
		return 0, fmt.Errorf("unexpected lua script return type %T: %v", result, result)
	}

	return int(deleted), nil
}

// Rehydrate implements port.CartLuaRepository.
// It executes the silent rehydrate.lua script to populate a cart from cold storage
// without emitting any events.
//
// Parameters:
//   - cartKey: Redis hash key, e.g. "{cart}:{user_id}"
//   - itemsJSON: JSON array of {sku_id, value} objects:
//     '[{"sku_id":"SKU001","value":"{\"product_id\":\"P1\",\"quantity\":2,\"updated_at\":\"1234\"}"}, ...]'
//   - ttlSeconds: TTL to set on the cart key (default 604800)
//
// Returns:
//   - error: nil on success
//
// Lua script guarantees NO XADD (silent), HSET only if field missing or
// incoming.updated_at > redis.updated_at, and EXPIRE at end.
func (r *CartLuaRepo) Rehydrate(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error {
	if itemsJSON == "" || itemsJSON == "[]" {
		// Nothing to rehydrate
		return nil
	}

	if ttlSeconds <= 0 {
		ttlSeconds = 604800 // default 7 days
	}

	result, err := r.client.Raw().Eval(ctx, RehydrateScript,
		[]string{cartKey}, // KEYS[1]
		itemsJSON,   // ARGV[1] — JSON array of items
		ttlSeconds,  // ARGV[2] — TTL in seconds
	).Result()
	if err != nil {
		return fmt.Errorf("%w: %v", port.ErrLuaScriptFailed, err)
	}

	// Rehydrate script returns integer count of items written (0 on silent failure)
	if result == nil {
		return nil
	}

	// Log written count for observability (can be hooked into metrics)
	if written, ok := result.(int64); ok && written > 0 {
		// Items were written — this is normal for a cache miss
	}

	return nil
}

// mapLuaError converts Lua script error strings to port-level sentinel errors.
// These errors are returned by Lua scripts via cjson.encode({err="..."}).
func mapLuaError(luaErr string) error {
	switch luaErr {
	case "Cart Full":
		return port.ErrCartFull
	case "Quantity Invalid":
		return port.ErrQuantityInvalid
	case "Cannot decrement non-existing item":
		return port.ErrCannotDecrementNonExisting
	case "Step Invalid":
		return port.ErrStepInvalid
	case "Invalid sku_ids":
		return fmt.Errorf("invalid sku_ids format in batch delete")
	default:
		return fmt.Errorf("lua script error: %s", luaErr)
	}
}

// CacheTTL returns the TTL for cart keys based on configuration.
func CacheTTL() time.Duration {
	return 7 * 24 * time.Hour // 604800 seconds
}

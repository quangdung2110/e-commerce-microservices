# Cart Service — Module 05: Lua Scripts

**Document:** 05 | **Module:** Lua Scripts — Redis Atomic Operations  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành  
**Phụ thuộc:** Không có (standalone text files, embed-only)

---

## 1. Mục tiêu Module

Cung cấp 3 Lua scripts nguyên tử cho Redis. Các script này được embed vào binary Go thông qua `//go:embed` và được gọi thông qua `redis.Script`. Module này chỉ chứa source Lua — logic invocation ở Module 06.

---

## 2. Embedding Mechanism

### 2.1. File Layout

```
internal/infrastructure/redis/scripts/
    ├── add_or_update.lua
    ├── batch_delete.lua
    └── rehydrate.lua
```

### 2.2. Go Embed Wrapper (`internal/infrastructure/redis/scripts/scripts.go`)

```go
package scripts

import _ "embed"

//go:embed add_or_update.lua
var AddOrUpdateScript string

//go:embed batch_delete.lua
var BatchDeleteScript string

//go:embed rehydrate.lua
var RehydrateScript string
```

---

## 3. Script 1: Add/Update Item (`add_or_update.lua`)

### 3.1. Purpose

Atomically adds a new item or updates an existing item's quantity. Executes HSET + XADD in the same Lua context. This is the primary write path for the Cart API.

### 3.2. Parameters

| Param | Index | Type | Description |
|-------|-------|------|-------------|
| KEYS[1] | - | - | Cart hash key with Hash Tag: `{cart}:{user_id}` |
| KEYS[2] | - | - | Global stream shard: `{cart}:events:{shard_id}` (same Hash Tag) |
| ARGV[1] | - | - | `sku_id` |
| ARGV[2] | - | - | Full JSON payload (built by backend) |
| ARGV[3] | - | - | `max_qty` (per-item limit, e.g. 100) |
| ARGV[4] | - | - | `max_skus` (cart capacity, e.g. 50) |
| ARGV[5] | - | - | `step` (quantity delta, e.g. +1 or -1) |
| ARGV[6] | - | - | `event_time_ms` (epoch milliseconds for business timestamp) |
| ARGV[7] | - | - | `user_id` (for event payload) |

### 3.3. Lua Source

```lua
--[[
  Cart Add/Update Script
  Ref: cart_caching_schema.md §3.1

  Guarantees:
    1. Atomic quantity accumulation (no race condition between concurrent requests)
    2. Cart capacity check (HLEN < max_skus before adding new SKU)
    3. Per-item quantity cap (new_qty <= max_qty)
    4. HSET + XADD in same atomic Lua context
    5. TTL refresh (EXPIRE 7 days = 604800 seconds)

  Error returns:
    {err="Cart Full"}      - cart at max SKU capacity
    {err="Step Invalid"}   - step is 0 or non-numeric
    {err="Quantity Invalid"} - new quantity <= 0
    {err="Cannot decrement non-existing item"} - trying to decrease qty of missing item
]]

-- 1. Anti-abuse: Check cart capacity before adding new SKU
if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return cjson.encode({err="Cart Full"})
end

-- 1.1. Validate step
local parsed_step = tonumber(ARGV[5])
if not parsed_step or parsed_step == 0 then
    return cjson.encode({err="Step Invalid"})
end

-- 2. Read current item
local current_item = redis.call('HGET', KEYS[1], ARGV[1])
local encoded_json = ARGV[2]
local is_new = 1

-- 3. Logic: Add or Update
if current_item then
    is_new = 0
    local old_item_obj = cjson.decode(current_item)
    local new_item_obj = cjson.decode(ARGV[2])

    local step = parsed_step
    local new_qty = old_item_obj.quantity + step
    local max_qty = tonumber(ARGV[3])

    -- Cap at max quantity per item
    if new_qty > max_qty then
        new_qty = max_qty
    end

    -- Reject zero or negative quantity
    if new_qty <= 0 then
        return cjson.encode({err="Quantity Invalid"})
    end

    -- Update object with new quantity and timestamp
    new_item_obj.quantity = new_qty
    new_item_obj.updated_at = tostring(ARGV[6])  -- String to avoid scientific notation in cjson
    new_item_obj.added_at = tostring(old_item_obj.added_at) -- Preserve original add timestamp

    encoded_json = cjson.encode(new_item_obj)
else
    -- Item doesn't exist: step must be positive to add new item
    if parsed_step < 0 then
        return cjson.encode({err="Cannot decrement non-existing item"})
    end
end

-- 4. HSET - Write item to Redis Hash
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)

-- 5. EXPIRE - Refresh TTL to 7 days (604800 seconds)
redis.call('EXPIRE', KEYS[1], 604800)

-- 6. Build event payload (inject cart_id + sku_id + user_id)
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
event_obj.user_id = ARGV[7]
local event_payload = cjson.encode(event_obj)

-- 7. Determine event type
local event_type = "CART_ITEM_ADDED"
if is_new == 0 then
    event_type = "CART_ITEM_UPDATED"
end

-- 8. XADD - Write event to global stream shard (NO MAXLEN - let Stream Trimmer handle cleanup)
--    Stream ID returned by XADD is the ordering token for downstream idempotency
local stream_id = redis.call('XADD', KEYS[2], '*',
    'event_type', event_type,
    'payload', event_payload)

return encoded_json
```

---

## 4. Script 2: Batch Delete (`batch_delete.lua`)

### 4.1. Purpose

Atomically deletes multiple SKUs from a cart. Used for checkout cleanup (after ORDER_PAID) and user-initiated batch remove. Emits one `CART_ITEM_DELETED` event per deleted SKU, and optionally one `CART_CLEARED` or `CART_CHECKED_OUT` event when the cart becomes empty.

### 4.2. Parameters

| Param | Index | Type | Description |
|-------|-------|------|-------------|
| KEYS[1] | - | - | Cart hash key with Hash Tag: `{cart}:{user_id}` |
| KEYS[2] | - | - | Global stream shard: `{cart}:events:{shard_id}` |
| ARGV[1] | - | - | `current_timestamp_ms` (epoch milliseconds) |
| ARGV[2] | - | - | `clear_event_type`: "CART_CLEARED" or "CART_CHECKED_OUT" |
| ARGV[3] | - | - | `user_id` (for event payload) |
| ARGV[4] | - | - | `sku_ids_json` (JSON array of SKU IDs to delete) |

### 4.3. Lua Source

```lua
--[[
  Cart Batch Delete Script
  Ref: cart_caching_schema.md §3.3

  Guarantees:
    1. HDEL only existing SKUs (skip non-existing)
    2. One CART_ITEM_DELETED event per deleted SKU
    3. CART_CLEARED or CART_CHECKED_OUT event when HLEN == 0
    4. DEL cart key if cart becomes empty
    5. No MAXLEN on XADD (Stream Trimmer handles cleanup)

  Error returns:
    {err="Invalid sku_ids"} - malformed JSON array
]]

local current_time_str = tostring(ARGV[1])
local user_id = ARGV[3]

-- Parse JSON array of SKU IDs
local sku_ids
local ok, decoded = pcall(cjson.decode, ARGV[4])
if not ok or type(decoded) ~= "table" then
    return cjson.encode({err="Invalid sku_ids"})
end
sku_ids = decoded

local deleted_count = 0
local skus_to_delete = {}  -- Accumulate SKUs for HDEL

-- Reusable event object to minimize allocations
local deleted_event_obj = {
    sku_id = "",
    cart_id = KEYS[1],
    user_id = user_id,
    deleted_at = current_time_str
}

-- 1. Scan through SKU list and HDEL only existing ones
for i = 1, #sku_ids do
    local sku_id = sku_ids[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)

    if current_item then
        table.insert(skus_to_delete, sku_id)
        deleted_count = deleted_count + 1

        -- Emit CART_ITEM_DELETED event for this SKU
        deleted_event_obj.sku_id = sku_id
        local deleted_payload = cjson.encode(deleted_event_obj)
        redis.call('XADD', KEYS[2], '*',
            'event_type', 'CART_ITEM_DELETED',
            'payload', deleted_payload)
    end
end

-- 2. Batch HDEL all existing SKUs at once
if deleted_count > 0 then
    redis.call('HDEL', KEYS[1], unpack(skus_to_delete))

    -- 3. Check if cart is now empty
    local remaining_items = redis.call('HLEN', KEYS[1])
    if remaining_items == 0 then
        -- Cart is empty: DELETE key and emit terminal event
        redis.call('DEL', KEYS[1])

        local clear_obj = {
            cart_id = KEYS[1],
            user_id = user_id
        }

        if ARGV[2] == 'CART_CHECKED_OUT' then
            clear_obj.checked_out_at = current_time_str
        else
            clear_obj.cleared_at = current_time_str
        end

        redis.call('XADD', KEYS[2], '*',
            'event_type', ARGV[2],
            'payload', cjson.encode(clear_obj))
    end
end

return deleted_count
```

---

## 5. Script 3: Silent Rehydrate (`rehydrate.lua`)

### 5.1. Purpose

Populates a cart cache from cold storage (PostgreSQL) after a cache miss. **This script does NOT emit any events** — it's purely a recovery operation. Uses timestamp guard to avoid overwriting newer Redis data during race conditions.

### 5.2. Parameters

| Param | Index | Type | Description |
|-------|-------|------|-------------|
| KEYS[1] | - | - | Cart hash key with Hash Tag: `{cart}:{user_id}` |
| ARGV[1] | - | - | JSON array of item candidates: `[{"sku_id":"...","value":"{...}"}, ...]` |
| ARGV[2] | - | - | TTL seconds (default: 604800) |

### 5.3. Lua Source

```lua
--[[
  Silent Rehydrate Script
  Ref: cart_caching_schema.md §3.6

  Guarantees:
    1. NO XADD (silent - no outbox events emitted)
    2. HSET only if field missing OR incoming.updated_at > redis.updated_at
    3. EXPIRE at end (write-recovery is allowed to reset TTL)

  This script is used in the cache-miss recovery path only.
]]

local cart_key = KEYS[1]
local ttl_sec = tonumber(ARGV[2]) or 604800

-- Parse the JSON array of items: [{sku_id, value}, ...]
local items
local ok, decoded = pcall(cjson.decode, ARGV[1])
if not ok or type(decoded) ~= "table" then
    return 0  -- Silent failure: return 0, caller handles error
end
items = decoded

local written = 0

for i = 1, #items do
    local sku_id = items[i].sku_id
    local incoming_json = items[i].value

    -- Parse incoming timestamp
    local incoming_obj
    local ok2, decoded2 = pcall(cjson.decode, incoming_json)
    if not ok2 then
        goto continue
    end
    incoming_obj = decoded2

    local incoming_updated = tonumber(incoming_obj.updated_at) or 0

    -- Read current Redis value
    local current_json = redis.call('HGET', cart_key, sku_id)

    -- Case 1: Item not in Redis -> write it
    if not current_json then
        redis.call('HSET', cart_key, sku_id, incoming_json)
        written = written + 1
    else
        -- Case 2: Item exists in Redis (potential race with concurrent writes)
        local ok3, current_obj = pcall(cjson.decode, current_json)
        if ok3 and current_obj then
            local current_updated = tonumber(current_obj.updated_at) or 0
            -- Only overwrite if incoming data is newer
            if incoming_updated > current_updated then
                redis.call('HSET', cart_key, sku_id, incoming_json)
                written = written + 1
            end
        end
    end

    ::continue::
end

-- Set TTL: Rehydrate is a write-recovery, so resetting TTL is allowed
redis.call('EXPIRE', cart_key, ttl_sec)

return written
```

---

## 6. Script Summary

| Script | File | XADD | Mutates Hash | EXPIRE | Use Case |
|--------|------|------|-------------|--------|----------|
| Add/Update | `add_or_update.lua` | Yes | Yes (HSET) | Yes (604800s) | AddItem API |
| Batch Delete | `batch_delete.lua` | Yes (per SKU + terminal) | Yes (HDEL) | No | BatchDelete, ORDER_PAID |
| Rehydrate | `rehydrate.lua` | **No** | Yes (HSET, conditional) | Yes (604800s) | Cache-miss recovery |

---

## 7. Common Gotchas và Debugging

| Gotcha | Solution |
|--------|----------|
| `unpack` fails with > 8000 items | Chunk HDEL into batches of ~1000 |
| `cjson.decode` fails silently | Wrap in `pcall` — use `goto continue` pattern |
| Timestamp as number causes scientific notation | Always convert to string: `tostring(ARGV[6])` |
| Missing Hash Tag causes CROSSSLOT | All keys MUST use `{cart}` hash tag |
| `XADD` without MAXLEN fills memory | Rely on Stream Trimmer (XTRIM MINID every 5m) |

---

## 8. Testing Strategy

| Script | Unit Test Approach | Tool |
|--------|-------------------|------|
| Add/Update | Mock Redis with miniredis; test quantity accumulation, cart capacity, timestamp preservation | `github.com/alicebob/miniredis` |
| Batch Delete | Test empty cart, partial deletion, full deletion, terminal event emission | `github.com/alicebob/miniredis` |
| Rehydrate | Test overwrite newer, overwrite older, skip missing | `github.com/alicebob/miniredis` |
| CROSSSLOT | Test with Redis Cluster mock (or real cluster in integration) | testcontainers |
| Concurrent writes | Run N goroutines hitting same key; verify final quantity | miniredis + sync.WaitGroup |

---

## 9. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `add_or_update.lua` | `internal/infrastructure/redis/scripts/add_or_update.lua` | Add/Update item script |
| `batch_delete.lua` | `internal/infrastructure/redis/scripts/batch_delete.lua` | Batch delete script |
| `rehydrate.lua` | `internal/infrastructure/redis/scripts/rehydrate.lua` | Silent rehydrate script |
| `scripts.go` | `internal/infrastructure/redis/scripts/scripts.go` | Embed directives |

---

## 10. Checklist triển khai Module 05

```markdown
## 05 - Lua Scripts
- [x] Tạo `internal/infrastructure/redis/scripts/add_or_update.lua` theo spec chi tiết ✅ (2026-05-25)
- [x] Tạo `internal/infrastructure/redis/scripts/batch_delete.lua` theo spec chi tiết ✅ (2026-05-25)
- [x] Tạo `internal/infrastructure/redis/scripts/rehydrate.lua` theo spec chi tiết ✅ (2026-05-25)
- [x] Tạo `internal/infrastructure/redis/scripts/scripts.go` với embed directives ✅ (2026-05-25)
- [ ] Viết unit tests với miniredis: quantity accumulation edge cases
- [ ] Viết unit tests với miniredis: cart capacity limits
- [ ] Viết unit tests với miniredis: batch delete edge cases
- [ ] Viết unit tests với miniredis: rehydrate timestamp guard
- [ ] Viết unit tests: concurrent writes race condition
- [ ] Verify Lua scripts pass `redis-cli SCRIPT LOAD`
- [x] Verify tất cả scripts compile và embed thành công ✅ (2026-05-25)
```

---

*Tiếp theo: [06-infra-redis.md](06-infra-redis.md) — Redis client, connection, Lua wrappers.*

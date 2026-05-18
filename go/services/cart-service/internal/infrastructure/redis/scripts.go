package redis

// Lua Scripts for Cart Operations

const (
	ScriptNameAddUpdate       = "add_update"
	ScriptNameBatchDelete     = "batch_delete"
	ScriptNameSilentRehydrate = "silent_rehydrate"
	ScriptNameStreamTrim      = "stream_trim"
)

// AddUpdateScript is the Lua script for adding/updating cart items
const AddUpdateScript = `
-- Lua Script: Add/Update Cart Item
--
-- KEYS[1]: cart_key - Cart hash key (e.g., {cart:user:12345})
-- KEYS[2]: outbox_stream - Outbox stream key (e.g., {cart:user:12345}:outbox)
--
-- ARGV[1]: sku_id
-- ARGV[2]: payload_json - Complete JSON payload for the item
-- ARGV[3]: max_qty - Maximum quantity per item (default: 100)
-- ARGV[4]: max_skus - Maximum unique SKUs in cart (default: 50)
-- ARGV[5]: step - Quantity change (+1 or -1)
-- ARGV[6]: event_time_ms - Event timestamp in milliseconds
-- ARGV[7]: user_id - User ID for event payload
--
-- Returns: encoded_json on success, "ERR:..." on error

-- 1. Block hack/bot from inflating Hash Key (limit total SKUs)
if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return "ERR:Cart Full"
end

-- 1.1 Validate step
local parsed_step = tonumber(ARGV[5])
if not parsed_step or parsed_step == 0 then
    return "ERR:Step Invalid"
end

-- 2. Read current item
local current_item = redis.call('HGET', KEYS[1], ARGV[1])
local encoded_json = ARGV[2]
local is_new = 1

-- 3. Accumulate logic
if current_item then
    is_new = 0
    local old_item_obj = cjson.decode(current_item)
    local new_item_obj = cjson.decode(ARGV[2])

    local step = parsed_step
    local new_qty = old_item_obj.quantity + step
    local max_qty = tonumber(ARGV[3])

    -- Cap at max quantity
    if new_qty > max_qty then
        new_qty = max_qty
    end

    -- Cannot go negative (will be handled by delete API)
    if new_qty <= 0 then
        return "ERR:Quantity Invalid"
    end

    -- Update object with latest snapshot data from request
    new_item_obj.quantity = new_qty
    -- Store timestamp as String to avoid scientific notation display issue with cjson
    new_item_obj.updated_at = tostring(ARGV[6])
    new_item_obj.added_at = tostring(old_item_obj.added_at) -- preserve original add timestamp

    encoded_json = cjson.encode(new_item_obj)
else
    -- New item: step must be positive (cannot decrement non-existing item)
    if parsed_step < 0 then
        return "ERR:Cannot decrement non-existing item"
    end
end

-- 4. Execute Hash Set
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)

-- 5. Extend cart TTL by 7 days (604800 seconds)
redis.call('EXPIRE', KEYS[1], 604800)

-- 6. Build Event Payload (append cart_id + sku_id + user_id for Consumer Worker)
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
event_obj.user_id = ARGV[7]
local event_payload = cjson.encode(event_obj)

local event_name = "CART_ITEM_ADDED"
if is_new == 0 then
    event_name = "CART_ITEM_UPDATED"
end

-- 7. Atomic: Push Event to Outbox Stream (NO MAXLEN to avoid losing unACKed events)
local stream_id = redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)

return encoded_json
`

// BatchDeleteScript is the Lua script for batch deleting cart items
const BatchDeleteScript = `
-- Lua Script: Batch Delete Cart Items
--
-- KEYS[1]: cart_key - Cart hash key (e.g., {cart:user:12345})
-- KEYS[2]: outbox_stream - Outbox stream key (e.g., {cart:user:12345}:outbox)
--
-- ARGV[1]: current_timestamp_ms - Current timestamp in milliseconds
-- ARGV[2]: clear_event_type - Event type when cart becomes empty
-- ARGV[3]: user_id - User ID for event payload
-- ARGV[4]: sku_ids_json - JSON array of SKU IDs to delete
--
-- Returns: deleted_count on success

local current_time_str = tostring(ARGV[1])
local user_id = ARGV[3]
local sku_ids = cjson.decode(ARGV[4])

local deleted_count = 0
local skus_to_delete = {}

local deleted_event_obj = {
    sku_id = "",
    cart_id = "",
    user_id = user_id,
    deleted_at = current_time_str
}
deleted_event_obj.cart_id = KEYS[1]

-- 1. Scan through sku_ids array and collect existing SKUs to delete
for i = 1, #sku_ids do
    local sku_id = sku_ids[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)

    if current_item then
        table.insert(skus_to_delete, sku_id)
        deleted_count = deleted_count + 1

        deleted_event_obj.sku_id = sku_id
        local deleted_payload = cjson.encode(deleted_event_obj)
        redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_DELETED', 'payload', deleted_payload)
    end
end

-- 2. Batch HDEL for items to remove completely
if deleted_count > 0 then
    redis.call('HDEL', KEYS[1], unpack(skus_to_delete))

    -- Cleanup Key if cart becomes empty
    local remaining_items = redis.call('HLEN', KEYS[1])
    if remaining_items == 0 then
        redis.call('DEL', KEYS[1])
        local clear_obj = {cart_id = KEYS[1], user_id = user_id}
        if ARGV[2] == 'CART_CHECKED_OUT' then
            clear_obj.checked_out_at = current_time_str
        else
            clear_obj.cleared_at = current_time_str
        end
        redis.call('XADD', KEYS[2], '*', 'event_type', ARGV[2], 'payload', cjson.encode(clear_obj))
    end
end

return deleted_count
`

// SilentRehydrateScript is the Lua script for silent rehydration (no event emission)
const SilentRehydrateScript = `
-- Lua Script: Silent Rehydrate
--
-- KEYS[1]: cart_key - Cart hash key (e.g., {cart:user:12345})
--
-- ARGV[1]: items_json_array - JSON array of items to rehydrate
-- ARGV[2]: ttl_seconds - TTL in seconds (default: 604800 = 7 days)
--
-- Returns: 1 on success

local cart_key = KEYS[1]
local ttl_sec = tonumber(ARGV[2]) or 604800
local arr = cjson.decode(ARGV[1])

for i = 1, #arr do
    local sku_id = arr[i].sku_id
    local incoming_json = arr[i].value
    local incoming_obj = cjson.decode(incoming_json)
    local incoming_updated = tonumber(incoming_obj.updated_at) or 0

    local current_json = redis.call('HGET', cart_key, sku_id)

    -- Case 1: Item not in Redis cart -> write
    if not current_json then
        redis.call('HSET', cart_key, sku_id, incoming_json)
    else
        -- Case 2: Item exists in Redis (potential race condition during merge)
        local ok, current_obj = pcall(cjson.decode, current_json)
        if ok and current_obj then
            local current_updated = tonumber(current_obj.updated_at) or 0
            if incoming_updated > current_updated then
                redis.call('HSET', cart_key, sku_id, incoming_json)
            end
        end
    end
end

redis.call('EXPIRE', cart_key, ttl_sec)
return 1
`

// StreamTrimScript is the Lua script for atomic empty stream cleanup
const StreamTrimScript = `
-- Lua Script: Stream Trim
--
-- KEYS[1]: stream_key - The outbox stream key to potentially delete
-- KEYS[2]: active_streams_key - ZSET containing active streams
--
-- ARGV[1]: stream_key - The stream key name (same as KEYS[1])
--
-- Returns: 1 if stream was deleted, 0 if skipped, -1 if stream has pending messages

local stream_key = ARGV[1]

-- Check if stream exists and has messages
local stream_len = redis.call('XLEN', stream_key)

if stream_len == 0 then
    -- Check if stream has consumer groups
    local groups = redis.call('XINFO', 'GROUPS', stream_key)

    if #groups == 0 then
        redis.call('DEL', stream_key)
        redis.call('ZREM', KEYS[2], stream_key)
        return 1
    else
        return 0
    end
end

return 0
`

-- Lua Script: Add/Update Cart Item
--
-- Atomic operation that adds a new item or updates an existing item's quantity
-- Prevents race conditions by executing atomically on Redis
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
    -- Store timestamp as string to avoid scientific notation display issue with cjson
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
-- Hash Value doesn't store cart_id/sku_id/user_id (redundant with Key/Field + ARGV),
-- but Event Payload sent to Kafka MUST have these so Worker knows where to write.
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
-- stream_id is read by Relay Worker from Redis message ID (msg.ID) for ordering/idempotency
local stream_id = redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)

return encoded_json

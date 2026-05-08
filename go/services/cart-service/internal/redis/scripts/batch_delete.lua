-- Lua Script: Batch Delete Cart Items
--
-- Atomically deletes multiple items from a cart and emits appropriate events
--
-- KEYS[1]: cart_key - Cart hash key (e.g., {cart:user:12345})
-- KEYS[2]: outbox_stream - Outbox stream key (e.g., {cart:user:12345}:outbox)
--
-- ARGV[1]: current_timestamp_ms - Current timestamp in milliseconds
-- ARGV[2]: clear_event_type - Event type when cart becomes empty:
--           "CART_CLEARED" - User manually clears cart
--           "CART_CHECKED_OUT" - Called from Order Event Consumer after ORDER_PAID
-- ARGV[3]: user_id - User ID for event payload
-- ARGV[4]: sku_ids_json - JSON array of SKU IDs to delete
--
-- Returns: deleted_count on success

local current_time_str = tostring(ARGV[1])
local user_id = ARGV[3]
local sku_ids = cjson.decode(ARGV[4])

local deleted_count = 0
local skus_to_delete = {} -- Array of SKUs to physically delete for batch HDEL

-- Reuse object to optimize memory in loop
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
    -- Use unpack to pass array as discrete parameters for HDEL
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

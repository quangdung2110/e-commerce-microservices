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

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

-- Cart Service — upsert item, Redis-only v2
--
-- Atomic boundary for a user write. All KEYS use the same {bNN} hash tag.
-- The public contract supplies a delta. Redis serializes deltas, so independent
-- clicks cannot overwrite a newer absolute quantity.
--
-- KEYS[1] items hash
-- KEYS[2] meta hash
-- KEYS[3] abandonment ZSET
-- KEYS[4] idempotency key
--
-- ARGV[1] sku_id
-- ARGV[2] delta (non-zero signed integer)
-- ARGV[3] product_snapshot_json, or empty when reusing an existing fresh item
-- ARGV[4] max_qty
-- ARGV[5] max_skus
-- ARGV[6] now_ms
-- ARGV[7] user_id
-- ARGV[8] cart_id
-- ARGV[9] user_token
-- ARGV[10] active_ttl_seconds
-- ARGV[11] abandoned_after_ms
-- ARGV[12] request_fingerprint

local items_key = KEYS[1]
local meta_key = KEYS[2]
local index_key = KEYS[3]
local idemp_key = KEYS[4]

local sku_id = ARGV[1]
local delta = tonumber(ARGV[2])
local snapshot_json = ARGV[3]
local max_qty = tonumber(ARGV[4])
local max_skus = tonumber(ARGV[5])
local now_ms = tonumber(ARGV[6])
local user_id = ARGV[7]
local cart_id = ARGV[8]
local user_token = ARGV[9]
local active_ttl = tonumber(ARGV[10])
local abandoned_after = tonumber(ARGV[11])
local fingerprint = ARGV[12]

local existing_idemp = redis.call('GET', idemp_key)
if existing_idemp then
    local record = cjson.decode(existing_idemp)
    if record.fingerprint ~= fingerprint then
        return {err = 'IDEMPOTENCY_CONFLICT'}
    end
    return record.response
end

if not delta or delta == 0 then
    return {err = 'DELTA_INVALID'}
end

local current_json = redis.call('HGET', items_key, sku_id)
local result

if current_json then
    local current = cjson.decode(current_json)

    -- Reducing an item never needs new catalog validation. Increasing it does.
    if delta > 0 and current.is_available == false then
        return {err = 'PRODUCT_NOT_AVAILABLE'}
    end
    if delta > 0 and tonumber(current.catalog_validation_expires_at_ms or '0') < now_ms
       and snapshot_json == '' then
        return {err = 'PRODUCT_SNAPSHOT_REQUIRED'}
    end

    local next_item = current
    if snapshot_json ~= '' then
        next_item = cjson.decode(snapshot_json)
        -- Historical fields remain immutable when Product data is refreshed.
        next_item.unit_price_at_add = current.unit_price_at_add
        next_item.added_at = current.added_at
    end
    if delta > 0 and next_item.is_available == false then
        return {err = 'PRODUCT_NOT_AVAILABLE'}
    end
    local available = tonumber(next_item.available_quantity_snapshot)
    local quantity = tonumber(current.quantity) + delta
    if quantity <= 0 then
        return {err = 'QUANTITY_INVALID'}
    end
    if quantity > max_qty then
        quantity = max_qty
    end
    if delta > 0 and available and quantity > available then
        return {err = 'INSUFFICIENT_DISPLAY_STOCK'}
    end
    next_item.quantity = quantity
    next_item.updated_at = tostring(now_ms)
    redis.call('HSET', items_key, sku_id, cjson.encode(next_item))
    result = cjson.encode({ sku_id = sku_id, quantity = quantity, updated_at = tostring(now_ms), created = false })
else
    if delta < 0 then
        return {err = 'ITEM_NOT_FOUND'}
    end
    if delta > max_qty then
        delta = max_qty
    end
    if snapshot_json == '' then
        return {err = 'PRODUCT_SNAPSHOT_REQUIRED'}
    end
    if redis.call('HLEN', items_key) >= max_skus then
        return {err = 'CART_FULL'}
    end

    local next_item = cjson.decode(snapshot_json)
    if next_item.is_available == false then
        return {err = 'PRODUCT_NOT_AVAILABLE'}
    end
    local available = tonumber(next_item.available_quantity_snapshot)
    if available and delta > available then
        return {err = 'INSUFFICIENT_DISPLAY_STOCK'}
    end
    next_item.quantity = delta
    next_item.added_at = tostring(now_ms)
    next_item.updated_at = tostring(now_ms)
    redis.call('HSET', items_key, sku_id, cjson.encode(next_item))
    result = cjson.encode({ sku_id = sku_id, quantity = delta, updated_at = tostring(now_ms), created = true })
end

redis.call('HSET', meta_key,
    'cart_id', cart_id,
    'user_id', user_id,
    'status', 'ACTIVE',
    'last_activity_at_ms', tostring(now_ms))
redis.call('ZADD', index_key, now_ms + abandoned_after, user_token)
redis.call('EXPIRE', items_key, active_ttl)
redis.call('EXPIRE', meta_key, active_ttl)
redis.call('SET', idemp_key, cjson.encode({ fingerprint = fingerprint, response = result }), 'EX', 86400)

return result

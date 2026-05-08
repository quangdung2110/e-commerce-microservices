-- Lua Script: Silent Rehydrate
--
-- Re-populates Redis cache from PostgreSQL cold store
-- CRITICAL: Does NOT emit events (NO XADD) to avoid replay garbage
--
-- KEYS[1]: cart_key - Cart hash key (e.g., {cart:user:12345})
--
-- ARGV[1]: items_json_array - JSON array of items to rehydrate:
--          [{"sku_id":"...", "value":"{...json...}"}, ...]
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
            -- If incoming is older, skip (keep Redis version)
        end
    end
end

redis.call('EXPIRE', cart_key, ttl_sec)
return 1

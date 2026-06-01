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

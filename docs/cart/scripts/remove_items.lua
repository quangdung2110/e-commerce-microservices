-- Cart Service — remove items, Redis-only v2
--
-- KEYS[1] items hash; KEYS[2] meta hash; KEYS[3] abandonment ZSET;
-- KEYS[4] optional HTTP idempotency key. Every supplied key shares one {bNN} hash tag.
--
-- ARGV[1] sku_ids_json; ARGV[2] user_token; ARGV[3] now_ms;
-- ARGV[4] touch_activity ("1" for a user delete, "0" for ORDER_PAID);
-- ARGV[5] active_ttl_seconds; ARGV[6] abandoned_after_ms;
-- ARGV[7] request_fingerprint; ARGV[8] idempotency_ttl_seconds (only for HTTP)

local items_key = KEYS[1]
local meta_key = KEYS[2]
local index_key = KEYS[3]
local idemp_key = KEYS[4]

local sku_ids = cjson.decode(ARGV[1])
local user_token = ARGV[2]
local now_ms = tonumber(ARGV[3])
local touch_activity = ARGV[4] == '1'
local active_ttl = tonumber(ARGV[5])
local abandoned_after = tonumber(ARGV[6])
local fingerprint = ARGV[7]
local idemp_ttl = tonumber(ARGV[8])

if idemp_key and idemp_key ~= '' then
    local existing_idemp = redis.call('GET', idemp_key)
    if existing_idemp then
        local record = cjson.decode(existing_idemp)
        if record.fingerprint ~= fingerprint then
            return {err = 'IDEMPOTENCY_CONFLICT'}
        end
        return record.response
    end
end

local deleted = 0
for i = 1, #sku_ids do
    deleted = deleted + redis.call('HDEL', items_key, sku_ids[i])
end

if redis.call('HLEN', items_key) == 0 then
    redis.call('DEL', items_key)
    redis.call('DEL', meta_key)
    redis.call('ZREM', index_key, user_token)
elseif touch_activity then
    redis.call('HSET', meta_key, 'status', 'ACTIVE', 'last_activity_at_ms', tostring(now_ms))
    redis.call('ZADD', index_key, now_ms + abandoned_after, user_token)
    redis.call('EXPIRE', items_key, active_ttl)
    redis.call('EXPIRE', meta_key, active_ttl)
end

local result = cjson.encode({ deleted_count = deleted })
if idemp_key and idemp_key ~= '' then
    redis.call('SET', idemp_key, cjson.encode({ fingerprint = fingerprint, response = result }), 'EX', idemp_ttl)
end
return result

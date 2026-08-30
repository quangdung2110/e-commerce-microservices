-- Cart Service — mark an inactive cart abandoned, Redis-only v2
--
-- KEYS[1] items hash; KEYS[2] meta hash; KEYS[3] abandonment ZSET;
-- KEYS[4] outbox stream. Every key carries the same {bNN} hash tag.
--
-- ARGV[1] user_token; ARGV[2] expected_deadline_ms; ARGV[3] now_ms;
-- ARGV[4] abandoned_ttl_seconds

local items_key = KEYS[1]
local meta_key = KEYS[2]
local index_key = KEYS[3]
local stream_key = KEYS[4]

local user_token = ARGV[1]
local expected_deadline = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local abandoned_ttl = tonumber(ARGV[4])

local deadline = redis.call('ZSCORE', index_key, user_token)
if not deadline or tonumber(deadline) ~= expected_deadline then
    return cjson.encode({ marked = false, reason = 'STALE_DEADLINE' })
end

if redis.call('EXISTS', meta_key) == 0 or redis.call('EXISTS', items_key) == 0 then
    redis.call('ZREM', index_key, user_token)
    return cjson.encode({ marked = false, reason = 'CART_MISSING' })
end

if redis.call('HGET', meta_key, 'status') ~= 'ACTIVE' or now_ms < expected_deadline then
    return cjson.encode({ marked = false, reason = 'NOT_DUE' })
end

local cycle = redis.call('HINCRBY', meta_key, 'abandonment_cycle', 1)
local cart_id = redis.call('HGET', meta_key, 'cart_id')
local user_id = redis.call('HGET', meta_key, 'user_id')
local payload = cjson.encode({
    cart_id = cart_id,
    user_id = user_id,
    abandonment_cycle = cycle,
    occurred_at_ms = tostring(now_ms)
})

redis.call('HSET', meta_key,
    'status', 'ABANDONED',
    'abandoned_at_ms', tostring(now_ms))
redis.call('ZREM', index_key, user_token)
redis.call('EXPIRE', items_key, abandoned_ttl)
redis.call('EXPIRE', meta_key, abandoned_ttl)

local stream_id = redis.call('XADD', stream_key, '*',
    'event_type', 'CART_ABANDONED',
    'payload', payload,
    'occurred_at_ms', tostring(now_ms))

return cjson.encode({ marked = true, stream_id = stream_id, abandonment_cycle = cycle })

-- Submit claim (data.md, "Claim Submit"): PREVIEWED -> SUBMITTING together with the immutable
-- Order payload and the recovery TTLs, in one script. When the payload cannot be written the
-- state does not change.
--
-- KEYS[1]  checkout:session:{ref}
-- KEYS[2]  checkout:submit-payload:{ref}
-- KEYS[3]  checkout:active:{user_id}
-- ARGV[1]  now (epoch ms)
-- ARGV[2]  user_id
-- ARGV[3]  attempt_id
-- ARGV[4]  payload ciphertext
-- ARGV[5]  recovery TTL (ms)
-- ARGV[6]  token ref (compared with the active pointer)
--
-- Returns one of
--   {'MISSING'}
--   {'NOT_OWNER'}
--   {'EXPIRED'}
--   {'CLAIMED'}
--   {state, claimed_at, attempt_started_at, attempt_id, revalidated_at, last_order_call_started_at}
--   for any state other than PREVIEWED (empty string for an absent field)
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
  return {'MISSING'}
end
if redis.call('HGET', KEYS[1], 'user_id') ~= ARGV[2] then
  return {'NOT_OWNER'}
end

if state ~= 'PREVIEWED' then
  local fields = redis.call('HMGET', KEYS[1],
    'claimed_at', 'attempt_started_at', 'attempt_id', 'revalidated_at', 'last_order_call_started_at')
  return {state, fields[1] or '', fields[2] or '', fields[3] or '', fields[4] or '', fields[5] or ''}
end

if tonumber(ARGV[1]) >= tonumber(redis.call('HGET', KEYS[1], 'expires_at')) then
  return {'EXPIRED'}
end

local ttl = tonumber(ARGV[5])
redis.call('HSET', KEYS[1],
  'state', 'SUBMITTING',
  'attempt_id', ARGV[3],
  'claimed_at', ARGV[1],
  'attempt_started_at', ARGV[1],
  'updated_at', ARGV[1])
redis.call('HDEL', KEYS[1], 'revalidated_at', 'last_order_call_started_at')
redis.call('SET', KEYS[2], ARGV[4], 'PX', ttl)
redis.call('PEXPIRE', KEYS[1], ttl)
if redis.call('GET', KEYS[3]) == ARGV[6] then
  redis.call('PEXPIRE', KEYS[3], ttl)
end
return {'CLAIMED'}

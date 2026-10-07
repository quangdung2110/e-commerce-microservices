-- Completion (data.md, "Hoan tat"): writes the terminal result and the terminal state in the
-- same script, so a reader never sees SUCCEEDED without its order_id. The first writer wins:
-- the outcome is decided by Order's durable truth or by the claimed attempt's own result, so
-- any actor may record it from SUBMITTING or RETRYABLE, and a second writer finds it done.
--
-- KEYS[1]  checkout:session:{ref}
-- KEYS[2]  checkout:result:{ref}
-- ARGV[1]  user_id
-- ARGV[2]  now (epoch ms)
-- ARGV[3]  terminal state: SUCCEEDED | CONFLICT | FAILED
-- ARGV[4]  order_id ('' unless SUCCEEDED)
-- ARGV[5]  stored problem ciphertext ('' when SUCCEEDED)
-- ARGV[6]  result TTL (ms)
--
-- Returns one of
--   {'FINALIZED'}
--   {'ALREADY', state}   another writer finished first; the stored result is authoritative
--   {'INVALID', state}   the token is PREVIEWED or SUPERSEDED and was never claimed
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'SUCCEEDED' or state == 'CONFLICT' or state == 'FAILED' then
  return {'ALREADY', state}
end
if state == 'PREVIEWED' or state == 'SUPERSEDED' then
  return {'INVALID', state}
end
-- state is SUBMITTING, RETRYABLE, or the session expired mid-flight: the result key stands alone.

redis.call('HSET', KEYS[2],
  'user_id', ARGV[1],
  'state', ARGV[3],
  'order_id', ARGV[4],
  'problem', ARGV[5],
  'finalized_at', ARGV[2])
redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[6]))
if state then
  redis.call('HSET', KEYS[1], 'state', ARGV[3], 'updated_at', ARGV[2])
end
return {'FINALIZED'}

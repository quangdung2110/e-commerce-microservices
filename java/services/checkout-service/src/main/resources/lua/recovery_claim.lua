-- Recovery claim (data.md, "Recovery claim"): RETRYABLE, or SUBMITTING past the owner timeout,
-- becomes SUBMITTING again under a new attempt_id. The payload is never rewritten and the
-- state never returns to PREVIEWED. The expected attempt id makes two concurrent recoverers
-- race on one winner: the loser sees SUBMITTING with a fresh attempt_started_at.
--
-- KEYS[1]  checkout:session:{ref}
-- ARGV[1]  now (epoch ms)
-- ARGV[2]  user_id
-- ARGV[3]  new attempt_id
-- ARGV[4]  owner timeout (ms)
-- ARGV[5]  attempt_id the caller observed ('' when none)
--
-- Returns one of
--   {'MISSING'} | {'NOT_OWNER'}
--   {'CLAIMED'}
--   {state, claimed_at, attempt_started_at, attempt_id, revalidated_at, last_order_call_started_at}
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
  return {'MISSING'}
end
if redis.call('HGET', KEYS[1], 'user_id') ~= ARGV[2] then
  return {'NOT_OWNER'}
end

local fields = redis.call('HMGET', KEYS[1],
  'claimed_at', 'attempt_started_at', 'attempt_id', 'revalidated_at', 'last_order_call_started_at')
local now = tonumber(ARGV[1])
local eligible = false
if state == 'RETRYABLE' then
  eligible = true
elseif state == 'SUBMITTING' then
  local startedAt = tonumber(fields[2] or fields[1] or '0')
  eligible = (now - startedAt) >= tonumber(ARGV[4])
end

if eligible and (fields[3] or '') == ARGV[5] then
  redis.call('HSET', KEYS[1],
    'state', 'SUBMITTING',
    'attempt_id', ARGV[3],
    'attempt_started_at', ARGV[1],
    'updated_at', ARGV[1])
  return {'CLAIMED'}
end
return {state, fields[1] or '', fields[2] or '', fields[3] or '', fields[4] or '', fields[5] or ''}

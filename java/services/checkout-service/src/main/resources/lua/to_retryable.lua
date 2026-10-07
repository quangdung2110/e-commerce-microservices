-- SUBMITTING -> RETRYABLE, only for the attempt that owns the token. A stale attempt must never
-- overwrite the state a newer attempt has written.
--
-- KEYS[1]  checkout:session:{ref}
-- ARGV[1]  attempt_id
-- ARGV[2]  now (epoch ms)
--
-- Returns 1 when the state changed, 0 otherwise.
if redis.call('HGET', KEYS[1], 'state') ~= 'SUBMITTING' then
  return 0
end
if redis.call('HGET', KEYS[1], 'attempt_id') ~= ARGV[1] then
  return 0
end
redis.call('HSET', KEYS[1], 'state', 'RETRYABLE', 'updated_at', ARGV[2])
return 1

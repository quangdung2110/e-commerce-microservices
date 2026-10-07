-- Records submit progress for the attempt that owns the token: revalidated_at and/or
-- last_order_call_started_at. A stale attempt (the token was re-claimed) changes nothing.
--
-- KEYS[1]  checkout:session:{ref}
-- ARGV[1]  attempt_id
-- ARGV[2]  now (epoch ms)
-- ARGV[3]  REVALIDATED | ORDER_CALL | BOTH
--
-- Returns 1 when the marks were written, 0 otherwise.
if redis.call('HGET', KEYS[1], 'state') ~= 'SUBMITTING' then
  return 0
end
if redis.call('HGET', KEYS[1], 'attempt_id') ~= ARGV[1] then
  return 0
end
if ARGV[3] == 'REVALIDATED' or ARGV[3] == 'BOTH' then
  redis.call('HSET', KEYS[1], 'revalidated_at', ARGV[2])
end
if ARGV[3] == 'ORDER_CALL' or ARGV[3] == 'BOTH' then
  redis.call('HSET', KEYS[1], 'last_order_call_started_at', ARGV[2])
end
redis.call('HSET', KEYS[1], 'updated_at', ARGV[2])
return 1

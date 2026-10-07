-- Read-only fast path of Preview, run before any fan-out (architecture.md section 3).
--
-- KEYS[1]      checkout:active:{user_id}
-- KEYS[2..n]   checkout:idem:{user_id}:{idem_ref} for every configured key version
-- ARGV[1]      session key prefix
-- ARGV[2]      submit-payload key prefix
-- ARGV[3]      request fingerprint
--
-- Returns one of
--   {'REPLAY', response_cipher}   same idempotency key and same fingerprint
--   {'MISMATCH'}                  same idempotency key, different fingerprint
--   {'IN_FLIGHT', payload_cipher} the user's current token is SUBMITTING or RETRYABLE
--   {'PROCEED'}
for i = 2, #KEYS do
  local fingerprint = redis.call('HGET', KEYS[i], 'fp')
  if fingerprint then
    if fingerprint == ARGV[3] then
      return {'REPLAY', redis.call('HGET', KEYS[i], 'resp') or ''}
    end
    return {'MISMATCH'}
  end
end

local current = redis.call('GET', KEYS[1])
if current then
  local state = redis.call('HGET', ARGV[1] .. current, 'state')
  if state == 'SUBMITTING' or state == 'RETRYABLE' then
    return {'IN_FLIGHT', redis.call('GET', ARGV[2] .. current) or ''}
  end
end
return {'PROCEED'}

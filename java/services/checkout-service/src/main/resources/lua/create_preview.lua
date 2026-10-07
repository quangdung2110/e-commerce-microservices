-- Wave 4 of Preview (data.md, "Tao Preview"): one atomic script that re-checks the in-flight
-- guard and the previous-token fence, writes the new session, supersedes the previous
-- PREVIEWED one, moves the active pointer and records the idempotent replay. A rejected
-- script writes nothing, so it never leaves an orphan session.
--
-- KEYS[1]      checkout:active:{user_id}
-- KEYS[2]      checkout:session:{new_ref}
-- KEYS[3..n]   checkout:idem:{user_id}:{idem_ref} for every configured key version; the
--              first one is the key this call writes (active key version)
-- ARGV[1]      session key prefix
-- ARGV[2]      submit-payload key prefix
-- ARGV[3]      now (epoch ms)
-- ARGV[4]      user_id
-- ARGV[5]      request fingerprint
-- ARGV[6]      previous token refs, comma separated, one per key version; '' when absent
-- ARGV[7]      new token ref
-- ARGV[8]      session TTL (ms)
-- ARGV[9]      idempotency TTL (ms)
-- ARGV[10]     quotation expires_at (epoch ms)
-- ARGV[11]     encryption key version
-- ARGV[12]     snapshot ciphertext
-- ARGV[13]     replay response ciphertext
--
-- Returns one of
--   {'CREATED', superseded}            superseded is 1 when a PREVIEWED token was replaced
--   {'REPLAY', response_cipher}        a concurrent request with the same key won
--   {'MISMATCH'}                       same idempotency key, different fingerprint
--   {'IN_FLIGHT', payload_cipher}      current token is SUBMITTING or RETRYABLE
--   {'ACTIVE_CHANGED'}                 previous token is not the current PREVIEWED one
for i = 3, #KEYS do
  local fingerprint = redis.call('HGET', KEYS[i], 'fp')
  if fingerprint then
    if fingerprint == ARGV[5] then
      return {'REPLAY', redis.call('HGET', KEYS[i], 'resp') or ''}
    end
    return {'MISMATCH'}
  end
end

local current = redis.call('GET', KEYS[1])
local currentState = false
if current then
  currentState = redis.call('HGET', ARGV[1] .. current, 'state')
  if currentState == 'SUBMITTING' or currentState == 'RETRYABLE' then
    return {'IN_FLIGHT', redis.call('GET', ARGV[2] .. current) or ''}
  end
end

if ARGV[6] ~= '' then
  local matches = false
  if current and currentState == 'PREVIEWED' then
    for ref in string.gmatch(ARGV[6], '[^,]+') do
      if ref == current then
        matches = true
      end
    end
  end
  if not matches then
    return {'ACTIVE_CHANGED'}
  end
end

local ttl = tonumber(ARGV[8])
redis.call('HSET', KEYS[2],
  'schema_version', '1',
  'user_id', ARGV[4],
  'state', 'PREVIEWED',
  'expires_at', ARGV[10],
  'key_version', ARGV[11],
  'snapshot', ARGV[12],
  'created_at', ARGV[3],
  'updated_at', ARGV[3])
redis.call('PEXPIRE', KEYS[2], ttl)

local superseded = 0
if current and currentState == 'PREVIEWED' then
  -- The previous record keeps its own TTL so a superseded token still answers 410.
  redis.call('HSET', ARGV[1] .. current, 'state', 'SUPERSEDED', 'updated_at', ARGV[3])
  superseded = 1
end

redis.call('SET', KEYS[1], ARGV[7], 'PX', ttl)

redis.call('HSET', KEYS[3], 'fp', ARGV[5], 'resp', ARGV[13])
redis.call('PEXPIRE', KEYS[3], tonumber(ARGV[9]))

return {'CREATED', superseded}

-- confirm_prepare.lua v1 — fence RESERVED -> CONFIRMING, gia hạn grace, KHÔNG move counter.
-- KEYS[1] promo:{id}:reservation:{order_id}
-- KEYS[2] promo:{id}:reservation:index
-- ARGV[1] order_id
-- ARGV[2] new expires_at_ms (score sau khi gia hạn grace)
-- ARGV[3] key TTL ms
-- Trả về {code, payload}: MISSING | CONFIRMING (replay) | PREPARED

local existing = redis.call('GET', KEYS[1])
if not existing then
  return {'MISSING'}
end
local decoded = cjson.decode(existing)
if decoded.state == 'CONFIRMING' then
  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]))
  redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
  return {'CONFIRMING', existing}
end
decoded.state = 'CONFIRMING'
decoded.expires_at_ms = tonumber(ARGV[2])
local updated = cjson.encode(decoded)
redis.call('SET', KEYS[1], updated, 'PX', tonumber(ARGV[3]))
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
return {'PREPARED', updated}

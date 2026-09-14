-- claim_acquire.lua v1 — limited claim acquisition (WF-PROMO-003, ADR-014/015).
-- KEYS[1] promo:{id}:runtime            HASH  claim-gate fields + budget counters
-- KEYS[2] promo:{id}:claim_pool         SET   AVAILABLE claim IDs
-- KEYS[3] promo:{id}:claimed_users      HASH  user_id -> claim_id
-- KEYS[4] promo:{id}:pending_claims:data  HASH  claim_id -> acquisition payload
-- KEYS[5] promo:{id}:pending_claims:index ZSET  stale deadline ms -> claim_id
-- ARGV[1] user_id
-- ARGV[2] expected campaign revision
-- ARGV[3] pending stale-after milliseconds
--
-- Redis TIME là linearization point. Gate được kiểm TRƯỚC SPOP nên không có
-- acquisition ngoài [opens_at_ms, closes_at_ms) hoặc với revision stale.
-- Trả về mảng: {code, claim_id, acquired_at_ms, revision, pending_payload}

local gate = redis.call('HMGET', KEYS[1], 'claim_enabled', 'claim_revision', 'opens_at_ms', 'closes_at_ms')
if not gate[1] or not gate[2] or not gate[3] or not gate[4] then
  return {'GATE_MISSING'}
end
if gate[1] ~= '1' then
  return {'GATE_DISABLED'}
end
if gate[2] ~= ARGV[2] then
  return {'GATE_STALE', '', '', gate[2]}
end

local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local opens = tonumber(gate[3])
local closes = tonumber(gate[4])
if now_ms < opens or now_ms >= closes then
  return {'OUT_OF_WINDOW', '', tostring(now_ms), gate[2]}
end

local existing = redis.call('HGET', KEYS[3], ARGV[1])
if existing then
  local pending = redis.call('HGET', KEYS[4], existing)
  return {'ALREADY_CLAIMED', existing, '', gate[2], pending or ''}
end

local claim_id = redis.call('SPOP', KEYS[2])
if not claim_id then
  return {'OUT_OF_STOCK'}
end

local payload = cjson.encode({
  claim_id = claim_id,
  user_id = ARGV[1],
  acquired_at_ms = now_ms,
  campaign_revision = tonumber(gate[2])
})
redis.call('HSET', KEYS[3], ARGV[1], claim_id)
redis.call('HSET', KEYS[4], claim_id, payload)
redis.call('ZADD', KEYS[5], now_ms + tonumber(ARGV[3]), claim_id)
return {'ACQUIRED', claim_id, tostring(now_ms), gate[2], payload}

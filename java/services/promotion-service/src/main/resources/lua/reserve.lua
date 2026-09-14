-- reserve.lua v1 — TRY: giữ quota/amount all-or-nothing (WF-PROMO-005, ADR-013).
-- KEYS[1] promo:{id}:runtime                 HASH  counters
-- KEYS[2] promo:{id}:reservation:{order_id}  STRING payload JSON
-- KEYS[3] promo:{id}:reservation:index       ZSET  expiry ms -> order_id
-- ARGV[1] order_id
-- ARGV[2] amount (integer VND)
-- ARGV[3] payload_hash (canonical request)
-- ARGV[4] payload JSON (state RESERVED)
-- ARGV[5] expires_at_ms (score)
-- ARGV[6] key TTL ms (business TTL + grace)
-- Trả về {code, payload}

local existing = redis.call('GET', KEYS[2])
if existing then
  local decoded = cjson.decode(existing)
  if decoded.payload_hash == ARGV[3] then
    return {'REPLAY', existing}
  end
  return {'PAYLOAD_MISMATCH', existing}
end

local rt = redis.call('HMGET', KEYS[1], 'total_redemption_quantity', 'total_budget_vnd',
  'used_quantity', 'used_vnd', 'reserved_quantity', 'reserved_vnd')
if not rt[3] then
  return {'RUNTIME_MISSING'}
end
local total_qty = tonumber(rt[1]) or -1
local total_vnd = tonumber(rt[2]) or -1
local used_qty = tonumber(rt[3]) or 0
local used_vnd = tonumber(rt[4]) or 0
local reserved_qty = tonumber(rt[5]) or 0
local reserved_vnd = tonumber(rt[6]) or 0
local amount = tonumber(ARGV[2])

if total_qty >= 0 and used_qty + reserved_qty + 1 > total_qty then
  return {'BUDGET_EXHAUSTED'}
end
if total_vnd >= 0 and used_vnd + reserved_vnd + amount > total_vnd then
  return {'BUDGET_EXHAUSTED'}
end

redis.call('HINCRBY', KEYS[1], 'reserved_quantity', 1)
redis.call('HINCRBY', KEYS[1], 'reserved_vnd', amount)
redis.call('SET', KEYS[2], ARGV[4], 'PX', tonumber(ARGV[6]))
redis.call('ZADD', KEYS[3], tonumber(ARGV[5]), ARGV[1])
return {'RESERVED', ARGV[4]}

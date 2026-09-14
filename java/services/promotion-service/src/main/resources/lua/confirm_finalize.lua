-- confirm_finalize.lua v1 — sau khi receipt đã commit PostgreSQL: reserved -> used.
-- KEYS[1] promo:{id}:runtime
-- KEYS[2] promo:{id}:reservation:{order_id}
-- KEYS[3] promo:{id}:reservation:index
-- ARGV[1] order_id
-- Trả về {code, amount}:
--   FINALIZED  counter đã move, payload/index đã xóa
--   MISSING    payload không còn (đã finalize trước đó hoặc mất) — caller reconcile từ DB, không increment mù
--   DRIFT      counter reserved không đủ để trừ — không mutate, caller reconcile

local existing = redis.call('GET', KEYS[2])
if not existing then
  return {'MISSING'}
end
local decoded = cjson.decode(existing)
local amount = tonumber(decoded.amount)
local rt = redis.call('HMGET', KEYS[1], 'reserved_quantity', 'reserved_vnd')
local reserved_qty = tonumber(rt[1]) or 0
local reserved_vnd = tonumber(rt[2]) or 0
if reserved_qty < 1 or reserved_vnd < amount then
  redis.call('DEL', KEYS[2])
  redis.call('ZREM', KEYS[3], ARGV[1])
  return {'DRIFT', tostring(amount)}
end
redis.call('HINCRBY', KEYS[1], 'reserved_quantity', -1)
redis.call('HINCRBY', KEYS[1], 'reserved_vnd', -amount)
redis.call('HINCRBY', KEYS[1], 'used_quantity', 1)
redis.call('HINCRBY', KEYS[1], 'used_vnd', amount)
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])
return {'FINALIZED', tostring(amount)}

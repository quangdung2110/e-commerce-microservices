-- cancel_release.lua v1 — nhả reservation pre-payment hoặc reservation hết hạn.
-- KEYS[1] promo:{id}:runtime
-- KEYS[2] promo:{id}:reservation:{order_id}
-- KEYS[3] promo:{id}:reservation:index
-- ARGV[1] order_id
-- ARGV[2] force: '1' cho phép release cả CONFIRMING (chỉ sweeper sau grace và sau khi đã
--         xác nhận không có durable receipt); API cancel luôn truyền '0'.
-- Trả về {code}: NO_RESERVATION | CONFIRMING | RELEASED | DRIFT

local existing = redis.call('GET', KEYS[2])
if not existing then
  return {'NO_RESERVATION'}
end
local decoded = cjson.decode(existing)
if decoded.state == 'CONFIRMING' and ARGV[2] ~= '1' then
  return {'CONFIRMING'}
end
local amount = tonumber(decoded.amount)
local rt = redis.call('HMGET', KEYS[1], 'reserved_quantity', 'reserved_vnd')
local reserved_qty = tonumber(rt[1]) or 0
local reserved_vnd = tonumber(rt[2]) or 0
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])
if reserved_qty < 1 or reserved_vnd < amount then
  return {'DRIFT'}
end
redis.call('HINCRBY', KEYS[1], 'reserved_quantity', -1)
redis.call('HINCRBY', KEYS[1], 'reserved_vnd', -amount)
return {'RELEASED'}

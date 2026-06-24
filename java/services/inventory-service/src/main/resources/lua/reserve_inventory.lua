-- reserve_inventory.lua
-- Context: ReserveInventoryActivity (pre-payment) - hold stock, increment reserved, decrement available,
--          XADD event ORDER_RESERVED atomically.
-- Idempotency: SETNX on KEYS[2]; if duplicate, returns {2, ''} and does NOT mutate.
-- Cluster: All 4 KEYS share hash-tag {sku:wh} so they live in the same slot.
--
-- KEYS[1] inventory:physic:{sku:wh}:stock                  stock hash (total, reserved, available, updated_at_ms)
-- KEYS[2] inventory:idemp:reserve:{sku:wh}:order:{order_id}
-- KEYS[3] inventory:stream:{sku:wh}:events                 stream outbox
-- KEYS[4] inventory:suspended:{sku:wh}                     suspension flag
-- ARGV[1] quantity            (int)
-- ARGV[2] now_ms              (epoch ms, used to set updated_at_ms)
-- ARGV[3] idemp_ttl_sec       (TTL for idempotency key, default 2592000 = 30 days)
-- ARGV[4] event_payload_json  (built in Java layer, M07)

if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end

if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])

if available < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'OUT_OF_STOCK'}
end

redis.call('HINCRBY', KEYS[1], 'available', -qty)
redis.call('HINCRBY', KEYS[1], 'reserved',  qty)
redis.call('HSET',     KEYS[1], 'updated_at_ms', ARGV[2])

redis.call('XADD', KEYS[3], '*',
  'event_type',  'INVENTORY_STOCK_MUTATION',
  'change_type', 'ORDER_RESERVED',
  'payload',     ARGV[4]
)

return {1, ''}

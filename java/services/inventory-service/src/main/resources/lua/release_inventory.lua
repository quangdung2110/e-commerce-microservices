-- release_inventory.lua
-- Context: RollbackInventoryActivity (Temporal compensation) - free a reservation,
--          decrement reserved, increment available, XADD event with caller-provided change_type.
-- Idempotency: SETNX on KEYS[2]; if duplicate, returns {2, ''} and does NOT mutate.
-- Cluster: All 4 KEYS share hash-tag {sku:wh} so they live in the same slot.
--
-- KEYS[1] inventory:physic:{sku:wh}:stock                  stock hash
-- KEYS[2] inventory:idemp:release:{sku:wh}:order:{order_id}
-- KEYS[3] inventory:stream:{sku:wh}:events                 stream outbox
-- KEYS[4] inventory:suspended:{sku:wh}                     suspension flag
-- ARGV[1] quantity            (int)
-- ARGV[2] now_ms              (epoch ms)
-- ARGV[3] idemp_ttl_sec       (TTL for idempotency key, default 2592000 = 30 days)
-- ARGV[4] event_payload_json  (built in Java layer, M07)
-- ARGV[5] change_type         ('ORDER_CANCELLED' | 'RESERVATION_EXPIRED')

if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end

if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local qty = tonumber(ARGV[1])

if reserved < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'RELEASE_UNDERFLOW'}
end

redis.call('HINCRBY', KEYS[1], 'reserved',  -qty)
redis.call('HINCRBY', KEYS[1], 'available',  qty)
redis.call('HSET',     KEYS[1], 'updated_at_ms', ARGV[2])

redis.call('XADD', KEYS[3], '*',
  'event_type',  'INVENTORY_STOCK_MUTATION',
  'change_type', ARGV[5],
  'payload',     ARGV[4]
)

return {1, ''}

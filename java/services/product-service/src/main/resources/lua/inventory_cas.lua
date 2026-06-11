-- inventory_cas.lua
-- Compare-And-Swap for inventory sync (Phase 1 of Two-Phase Lock-Free Architecture)
-- KEYS[1] = sku cache key (product:sku:{sku_id})
-- KEYS[2] = inventory pending ZSET key (product:inventory:pending)
-- ARGV[1] = sku_id
-- ARGV[2] = new_quantity
-- ARGV[3] = event_timestamp (epoch millis)
-- ARGV[4] = sku_json (full JSON string for cache overwrite)
-- Returns: 1 if CAS succeeded, 0 if stale event, -1 if lock held

-- Extract last_inventory_sync_at from cached SKU
local cached = redis.call('GET', KEYS[1])
if cached then
    local cached_data = cjson.decode(cached)
    local cached_sync_at = cached_data.last_inventory_sync_at or 0
    if tonumber(ARGV[3]) <= tonumber(cached_sync_at) then
        return 0  -- stale event, skip
    end
end

-- Overwrite SKU cache (soft invalidation - overwrite on every event)
redis.call('SET', KEYS[1], ARGV[4])

-- Add to pending ZSET for Phase 2 drain
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])

return 1

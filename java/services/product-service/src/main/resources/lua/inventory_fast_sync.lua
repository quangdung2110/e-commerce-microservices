-- inventory_fast_sync.lua
-- Fast-path inventory sync for Phase 1 of Two-Phase Lock-Free Architecture
-- KEYS[1] = product:sku:{sku_id}
-- KEYS[2] = product:inventory:pending
-- ARGV[1] = sku_id
-- ARGV[2] = available_quantity
-- ARGV[3] = event_timestamp (epoch millis, derived from CloudEvents wrapper `timestamp`
--            ISO 8601 UTC via Instant.parse(...).toEpochMilli() in the consumer.
--            Payload intentionally does NOT carry event_timestamp anymore.)
-- ARGV[4] = product_id
-- ARGV[5] = sku_status
-- ARGV[6] = sku_json (full JSON string for cache overwrite)
-- ARGV[7] = member (skuId:timestamp for ZSET)
-- Returns: 1 = applied, 0 = stale, -1 = cache miss

-- Extract last_inventory_sync_at from cached SKU JSON
local cached = redis.call('GET', KEYS[1])

if cached then
    -- Parse cached JSON to check staleness
    local cached_data = cjson.decode(cached)
    local cached_sync_at = cached_data.last_inventory_sync_at or 0

    -- Staleness guard: reject if event timestamp is not newer
    if tonumber(ARGV[3]) <= tonumber(cached_sync_at) then
        return 0  -- stale event, skip
    end

    -- Product ID guard: verify product_id matches
    if cached_data.product_id and cached_data.product_id ~= ARGV[4] then
        return 0  -- product ID mismatch, skip
    end
end

if cached then
    -- Cache hit: overwrite cache (soft invalidation)
    redis.call('SET', KEYS[1], ARGV[6])
else
    -- Cache miss: only ZADD to pending queue, do not write partial cache
    -- The event handler worker will warm the cache from DB
    redis.call('ZADD', KEYS[2], ARGV[3], ARGV[7])
    return -1
end

-- ZADD to pending queue for Phase 2 drain
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[7])

return 1

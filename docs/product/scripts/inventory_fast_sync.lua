-- inventory_fast_sync.lua
--
-- DEPRECATED. This file is retained only as evidence for archived ADR-003.
-- It is not part of the current Product Service design and must not be called
-- by a Product Service deployment.
--
-- Phase 1 của Inventory Sync (xem docs/product/workflows.md#wf-product-009 và
-- docs/product/decisions/ADR-003-two-phase-lock-free-inventory-sync.md).
--
-- Cập nhật atomic các field tồn kho trong point-read cache và đăng ký SKU vào
-- hàng đợi pending sync để Phase 2 ghi xuống PostgreSQL. Script này KHÔNG được
-- ghi các field giá, price_updated_at hay product_status: chúng thuộc quyền ghi
-- của API in-band và Event Handler (docs/product/data.md#7-caching).
--
-- KEYS[1] = product:sku:{sku_id}
-- KEYS[2] = product:inventory:pending            (ZSET dùng chung)
-- ARGV[1] = event_timestamp (epoch millis, convert từ CloudEvents `timestamp`)
-- ARGV[2] = available_quantity
-- ARGV[3] = product_id
-- ARGV[4] = sku_status do consumer suy ra từ event
-- ARGV[5] = pending member, dạng "{sku_id}:{event_timestamp}"
--
-- Trả về:
--    1 = đã apply (cache hit, event mới hơn, product_id khớp)
--    0 = bỏ qua (event cũ hoặc product_id không khớp)
--   -1 = cache miss (không ghi field tồn kho, chỉ đăng ký pending sync)

local cacheKey      = KEYS[1]
local pendingZSet   = KEYS[2]

local eventTs       = tonumber(ARGV[1])
local availableQty  = tonumber(ARGV[2])
local productId     = ARGV[3]
local skuStatus     = ARGV[4]
local pendingMember = ARGV[5]

local CACHE_TTL_SECONDS   = 3600
local PENDING_TTL_SECONDS = 86400

local existingJson = redis.call('JSON.GET', cacheKey)

if not existingJson then
    -- Cache miss: Phase 2 sẽ query DB và nạp lại snapshot đầy đủ.
    redis.call('ZADD', pendingZSet, eventTs, pendingMember)
    redis.call('EXPIRE', pendingZSet, PENDING_TTL_SECONDS)
    return -1
end

local data     = cjson.decode(existingJson)
local cachedTs = tonumber(data.last_inventory_sync_at) or 0

-- Staleness guard: chỉ apply event mới hơn mốc đã ghi nhận.
if eventTs <= cachedTs then
    return 0
end

-- Product guard: cache thuộc product khác nghĩa là dữ liệu đã lệch, không ghi đè.
if data.product_id and data.product_id ~= '' and data.product_id ~= productId then
    return 0
end

-- Suy ra sku_status theo tồn kho. INACTIVE do admin đặt nên không bị tự động đổi.
local newSkuStatus = skuStatus
if availableQty == 0 then
    newSkuStatus = 'OUT_OF_STOCK'
elseif availableQty > 0 and newSkuStatus == 'OUT_OF_STOCK' then
    newSkuStatus = 'ACTIVE'
end

-- Chỉ ghi field tồn kho; mọi field khác giữ nguyên giá trị đang có trong cache.
data.display_quantity        = availableQty
data.sku_status              = newSkuStatus
data.is_available            = availableQty > 0
data.last_inventory_sync_at  = eventTs
data.product_id              = productId

redis.call('JSON.SET', cacheKey, '$', cjson.encode(data))
redis.call('EXPIRE', cacheKey, CACHE_TTL_SECONDS)
redis.call('ZADD', pendingZSet, eventTs, pendingMember)
redis.call('EXPIRE', pendingZSet, PENDING_TTL_SECONDS)

return 1

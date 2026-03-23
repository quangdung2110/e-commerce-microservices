-- ============================================================================
-- Script 3: Xác nhận Mua Thành Công (Commit Redeem)
-- Theo thiết kế: redis_caching_design.md - Section 3, Script 3
-- Chạy khi Kafka báo "Order Success" (CONFIRM Phase)
-- ============================================================================
-- KEYS[1] = "promo:{promotion_id}:budget"                                  (Hash Tag: {promotion_id})
-- KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:redeem"           (Hash Tag: {promotion_id})
-- ARGV[1] = quantity (Thường là 1)
-- ARGV[2] = discount_amount (Số tiền)

-- Chống lặp Event: Nếu lệnh này đã từng chạy cho order này rồi, tự động bỏ qua
if redis.call("SETNX", KEYS[2], "1") == 0 then
    return 1 -- Báo OK coi như đã xử lý thành công
end
redis.call("EXPIRE", KEYS[2], 86400) -- Hạn khoá Idempotency 1 ngày

local lockQty = tonumber(ARGV[1])
local lockAmount = tonumber(ARGV[2])

-- Trừ ở rổ Reserved, chuyển sang rổ Redeemed
redis.call("HINCRBY", KEYS[1], "reserved_qty", -lockQty)
redis.call("HINCRBY", KEYS[1], "redeemed_qty", lockQty)

redis.call("HINCRBYFLOAT", KEYS[1], "reserved_budget", -lockAmount)
redis.call("HINCRBYFLOAT", KEYS[1], "redeemed_budget", lockAmount)

return 1

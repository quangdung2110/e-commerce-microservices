-- ============================================================================
-- Script 4: Nhả Ngân sách (Rollback / Release Reservation)
-- Theo thiết kế: redis_caching_design.md - Section 3, Script 4
-- Chạy khi Kafka báo "Order Cancelled" hoặc TTL 15 phút hết hạn (CANCEL Phase)
-- ============================================================================
-- KEYS[1] = "promo:{promotion_id}:budget"                                  (Hash Tag: {promotion_id})
-- KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:cancel"           (Hash Tag: {promotion_id})
-- ARGV[1] = quantity (Đã khóa)
-- ARGV[2] = discount_amount (Đã khóa)

-- Chống lặp Event:
if redis.call("SETNX", KEYS[2], "1") == 0 then
    return 1 -- Báo OK coi như đã xử lý thành công
end
redis.call("EXPIRE", KEYS[2], 86400)

local unlockQty = tonumber(ARGV[1])
local unlockAmount = tonumber(ARGV[2])

-- Gặp lỗi/huỷ đơn -> Trừ rổ Reserved đi, tự động trả ngân sách về hồ Available
redis.call("HINCRBY", KEYS[1], "reserved_qty", -unlockQty)
redis.call("HINCRBYFLOAT", KEYS[1], "reserved_budget", -unlockAmount)

return 1

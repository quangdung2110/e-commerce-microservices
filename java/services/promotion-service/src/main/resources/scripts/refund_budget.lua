-- ============================================================================
-- Script 5: Hoàn Ngân sách sau Đổi Trả Hàng (Refund Budget)
-- Theo thiết kế: redis_caching_design.md - Section 3, Script 5
-- Chạy khi Kafka báo "Order Refunded" — sau khi đơn đã Redeemed thành công
-- Khác biệt với Script 4 (Cancel): Cancel nhả reserved, Refund nhả redeemed
-- ============================================================================
-- KEYS[1] = "promo:{promotion_id}:budget"                                  (Hash Tag: {promotion_id})
-- KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:refund"           (Hash Tag: {promotion_id})
-- ARGV[1] = quantity (Đã redeemed trước đó)
-- ARGV[2] = discount_amount (Số tiền cần hoàn)

-- Chống lặp Event:
if redis.call("SETNX", KEYS[2], "1") == 0 then
    return 1 -- Đã xử lý rồi
end
redis.call("EXPIRE", KEYS[2], 86400)

local refundQty = tonumber(ARGV[1])
local refundAmount = tonumber(ARGV[2])

-- Trừ ở rổ Redeemed -> Ngân sách tự động trở về Available (limit - reserved - redeemed)
redis.call("HINCRBY", KEYS[1], "redeemed_qty", -refundQty)
redis.call("HINCRBYFLOAT", KEYS[1], "redeemed_budget", -refundAmount)

return 1

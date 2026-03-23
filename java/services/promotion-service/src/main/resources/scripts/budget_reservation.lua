-- ============================================================================
-- Script 2: Thuật toán Giữ chỗ Ngân Sách (Budget Reservation)
-- Theo thiết kế: redis_caching_design.md - Section 3, Script 2
-- Chạy lúc User bấm "Xác nhận Đặt hàng" (TRY Phase / Saga)
-- ============================================================================
-- KEYS[1] = "promo:{promotion_id}:budget"  (Hash Tag: {promotion_id})
-- ARGV[1] = quantity (Số lượng cần khóa, thường là 1)
-- ARGV[2] = discount_amount (Số tiền chuẩn bị giảm)

local budgetKey = KEYS[1]
local lockQty = tonumber(ARGV[1])
local lockAmount = tonumber(ARGV[2])

-- Tối ưu hoá: Dùng lệnh HMGET lấy 1 lần mảng 6 giá trị thay vì gọi HGET 6 lần
local data = redis.call("HMGET", budgetKey, "limit_qty", "reserved_qty", "redeemed_qty", "limit_budget", "reserved_budget", "redeemed_budget")

local limitQty = tonumber(data[1] or "0")
local reservedQty = tonumber(data[2] or "0")
local redeemedQty = tonumber(data[3] or "0")

local limitBudget = tonumber(data[4] or "0")
local reservedBudget = tonumber(data[5] or "0")
local redeemedBudget = tonumber(data[6] or "0")

local availableQty = limitQty - reservedQty - redeemedQty
local availableBudget = limitBudget - reservedBudget - redeemedBudget

-- Kiểm tra chéo cả 2 điều kiện: Còn đủ Số lượng VÀ Còn đủ Tiền
if availableQty >= lockQty and availableBudget >= lockAmount then
    -- Đủ ngân sách -> Tăng số lượng và tiền Reserved lên
    redis.call("HINCRBY", budgetKey, "reserved_qty", lockQty)
    -- HINCRBYFLOAT dùng cho giá trị thập phân (tiền tệ)
    redis.call("HINCRBYFLOAT", budgetKey, "reserved_budget", lockAmount)
    return 1 -- OK
else
    return 0 -- Failed (Vượt ngân sách số lượng hoặc tiền)
end

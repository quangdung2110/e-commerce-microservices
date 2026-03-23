-- ============================================================================
-- Script 1: Thuật toán Giật Mã Flash Sale O(1) (SPOP Claiming)
-- Theo thiết kế: redis_caching_design.md - Section 3, Script 1
-- Chạy khi 10,000 user đập nút "Lưu Mã" lúc 00:00:00
-- ============================================================================
-- KEYS[1]: promo:{promotion_id}:claimed:user:{user_id}  (Hash Tag: {promotion_id})
-- KEYS[2]: promo:{promotion_id}:claim_pool               (Hash Tag: {promotion_id})
-- KEYS[3]: promo:{promotion_id}:pending_claims            (Hash Tag: {promotion_id})
-- ARGV[1]: user_id:timestamp (Ví dụ: "99:1708899200")

-- Bước 1: Check xem User này đã từng giật mã này chưa?
if redis.call("EXISTS", KEYS[1]) == 1 then
    return -1 -- Lỗi: Bọn tao biết mầy giật rồi nha (Chống user spam)
end

-- Bước 2: Thò tay bóc ngẫu nhiên 1 suất (ID) ra khỏi hồ
local claimId = redis.call("SPOP", KEYS[2])

if claimId then
    -- Bước 3: Đánh dấu User này đã giành được cái claimId đó.
    redis.call("SET", KEYS[1], claimId, "EX", 86400) -- Hết hạn sau 1 ngày (dọn rác)

    -- Bước 4: Lưu Pending Claim để phòng hờ Crash App không quăng được Kafka
    redis.call("HSET", KEYS[3], claimId, ARGV[1])

    return claimId -- Trả về ID thẻ cào (VD: 549) để Backend ném vô Kafka ghi DB
else
    return -2 -- Sold Out (Hồ đã cạn nước)
end

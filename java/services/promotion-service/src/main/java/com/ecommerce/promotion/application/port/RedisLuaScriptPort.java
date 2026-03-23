package com.ecommerce.promotion.application.port;

import java.math.BigDecimal;

/**
 * Port interface cho Redis Lua Script execution.
 * Theo thiết kế redis_caching_design.md - Section 3: Các Script Xử lý Nguyên tử.
 *
 * Infrastructure layer sẽ implement port này bằng Lettuce RedisScriptingCommands.
 */
public interface RedisLuaScriptPort {

    // =========================================================================
    // Script 1: SPOP Claiming (Flash Sale)
    // Theo thiết kế redis_caching_design.md - Script 1
    // =========================================================================

    /**
     * Giật mã Flash Sale O(1) bằng Redis SPOP.
     *
     * @param promotionId ID của promotion
     * @param userId      ID của user
     * @return claimId nếu thành công, -1 nếu đã claimed, -2 nếu sold out
     */
    long spopClaim(Long promotionId, Long userId);

    /**
     * Xóa pending claim sau khi Kafka Worker ghi DB thành công.
     * Theo thiết kế redis_caching_design.md - Script 1: HDEL.
     */
    void removePendingClaim(Long promotionId, Long claimId);

    // =========================================================================
    // Script 2: Budget Reservation (TRY Phase)
    // Theo thiết kế redis_caching_design.md - Script 2
    // =========================================================================

    /**
     * Giữ chỗ ngân sách (Reserve).
     *
     * @param promotionId    ID promotion
     * @param quantity       Số lượng cần khóa (thường 1)
     * @param discountAmount Số tiền cần khóa
     * @return true nếu thành công (đủ budget), false nếu hết budget
     */
    boolean reserveBudget(Long promotionId, int quantity, BigDecimal discountAmount);

    // =========================================================================
    // Script 3: Commit Redeem (CONFIRM Phase)
    // Theo thiết kế redis_caching_design.md - Script 3
    // =========================================================================

    /**
     * Chuyển ngân sách từ Reserved sang Redeemed.
     *
     * @param promotionId    ID promotion
     * @param orderId        ID đơn hàng (dùng cho idempotency key)
     * @param quantity       Số lượng
     * @param discountAmount Số tiền
     * @return true nếu thành công
     */
    boolean commitRedeem(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount);

    // =========================================================================
    // Script 4: Rollback Cancel (CANCEL Phase)
    // Theo thiết kế redis_caching_design.md - Script 4
    // =========================================================================

    /**
     * Nhả ngân sách Reserved khi đơn hàng bị hủy.
     *
     * @param promotionId    ID promotion
     * @param orderId        ID đơn hàng (dùng cho idempotency key)
     * @param quantity       Số lượng đã khóa
     * @param discountAmount Số tiền đã khóa
     * @return true nếu thành công
     */
    boolean rollbackCancel(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount);

    // =========================================================================
    // Script 5: Refund Budget
    // Theo thiết kế redis_caching_design.md - Script 5
    // =========================================================================

    /**
     * Hoàn ngân sách Redeemed khi đổi trả hàng.
     *
     * @param promotionId    ID promotion
     * @param orderId        ID đơn hàng (dùng cho idempotency key)
     * @param quantity       Số lượng đã redeemed
     * @param discountAmount Số tiền đã redeemed
     * @return true nếu thành công
     */
    boolean refundBudget(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount);

    // =========================================================================
    // Reservation TTL Tracking
    // Theo thiết kế redis_caching_design.md - Section 2.4
    // =========================================================================

    /**
     * Tạo Reservation TTL Key (15 phút) sau khi Reserve thành công.
     * Key: promo:{promotion_id}:reservation:{order_id}
     */
    void setReservationTtl(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount, long ttlSeconds);

    /**
     * Xóa Reservation Key sau khi Redeem/Cancel xong.
     */
    void deleteReservationKey(Long promotionId, Long orderId);

    /**
     * Kiểm tra Reservation Key còn tồn tại không.
     * Dùng cho Reservation TTL Sweep Job (Section 2.4 Background Job Sweep).
     *
     * @return true nếu key vẫn còn (chưa đến hạn), false nếu đã expire
     */
    boolean existsReservationKey(Long promotionId, Long orderId);

    // =========================================================================
    // Budget Hash Warm-up
    // Theo thiết kế redis_caching_design.md - Section 2.2: Warm-up
    // =========================================================================

    /**
     * Nạp Budget lên Redis Hash khi Admin tạo/kích hoạt Promotion.
     * Chỉ nạp nếu key chưa tồn tại (HSETNX logic).
     */
    void warmUpBudget(Long promotionId, int limitQty, BigDecimal limitBudget);

    // =========================================================================
    // Claim Pool Pre-warming
    // Theo thiết kế redis_caching_design.md - Section 2.3: Pre-warming
    // =========================================================================

    /**
     * Push claim IDs vào Redis Set cho Flash Sale SPOP.
     */
    void warmUpClaimPool(Long promotionId, java.util.List<Long> claimIds);

    // =========================================================================
    // Pending Claims
    // Theo thiết kế redis_caching_design.md - Fault Tolerance Job
    // =========================================================================

    /**
     * Lấy tất cả pending claims cho 1 promotion.
     * @return Map<claimId, "userId:timestamp">
     */
    java.util.Map<Long, String> getPendingClaims(Long promotionId);
}

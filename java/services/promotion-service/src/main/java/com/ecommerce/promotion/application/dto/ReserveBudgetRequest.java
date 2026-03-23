package com.ecommerce.promotion.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request payload cho API /reserve (TRY Phase).
 * Theo thiết kế promotion_workflow.md - Luồng 4.A: Xác nhận Giỏ hàng.
 * Order Service gọi khi user bấm "Xác nhận Đặt hàng".
 */
public class ReserveBudgetRequest {

    private Long orderId;
    private Long userId;
    private List<ReservationItemDto> promotions;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public List<ReservationItemDto> getPromotions() { return promotions; }
    public void setPromotions(List<ReservationItemDto> promotions) { this.promotions = promotions; }

    /**
     * 1 promotion cần reserve budget.
     */
    public static class ReservationItemDto {
        private Long promotionId;
        private BigDecimal discountAmount;
        private BigDecimal platformFunding;
        private BigDecimal sellerFunding;

        public Long getPromotionId() { return promotionId; }
        public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
        public BigDecimal getPlatformFunding() { return platformFunding; }
        public void setPlatformFunding(BigDecimal platformFunding) { this.platformFunding = platformFunding; }
        public BigDecimal getSellerFunding() { return sellerFunding; }
        public void setSellerFunding(BigDecimal sellerFunding) { this.sellerFunding = sellerFunding; }
    }
}

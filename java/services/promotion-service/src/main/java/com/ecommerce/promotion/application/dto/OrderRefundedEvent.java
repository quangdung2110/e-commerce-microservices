package com.ecommerce.promotion.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound Event DTO cho ORDER_REFUNDED.
 * Theo thiết kế promotion_events.md - Section 3.3.
 */
public class OrderRefundedEvent {

    private Long orderId;
    private Long userId;
    private List<PromotionAppliedDto> promotionsApplied;
    private String refundedAt;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public List<PromotionAppliedDto> getPromotionsApplied() { return promotionsApplied; }
    public void setPromotionsApplied(List<PromotionAppliedDto> promotionsApplied) { this.promotionsApplied = promotionsApplied; }
    public String getRefundedAt() { return refundedAt; }
    public void setRefundedAt(String refundedAt) { this.refundedAt = refundedAt; }

    public static class PromotionAppliedDto {
        private Long promotionId;
        private BigDecimal discountAmount;

        public Long getPromotionId() { return promotionId; }
        public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    }
}

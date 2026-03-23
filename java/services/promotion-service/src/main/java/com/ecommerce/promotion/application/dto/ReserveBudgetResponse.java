package com.ecommerce.promotion.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response cho API /reserve.
 * Theo thiết kế promotion_workflow.md - Luồng 4.A.
 */
public class ReserveBudgetResponse {

    private Long orderId;
    private boolean success;
    private long reservationTtlSeconds;
    private List<ReservedItemDto> reservedPromotions;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public long getReservationTtlSeconds() { return reservationTtlSeconds; }
    public void setReservationTtlSeconds(long reservationTtlSeconds) { this.reservationTtlSeconds = reservationTtlSeconds; }
    public List<ReservedItemDto> getReservedPromotions() { return reservedPromotions; }
    public void setReservedPromotions(List<ReservedItemDto> reservedPromotions) { this.reservedPromotions = reservedPromotions; }

    public static class ReservedItemDto {
        private Long promotionId;
        private BigDecimal discountAmount;
        private boolean reserved;
        private String failReason;

        public Long getPromotionId() { return promotionId; }
        public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
        public boolean isReserved() { return reserved; }
        public void setReserved(boolean reserved) { this.reserved = reserved; }
        public String getFailReason() { return failReason; }
        public void setFailReason(String failReason) { this.failReason = failReason; }
    }
}

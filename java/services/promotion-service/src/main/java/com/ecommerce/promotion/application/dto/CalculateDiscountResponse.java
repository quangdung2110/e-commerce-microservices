package com.ecommerce.promotion.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response cho API /calculate-discount.
 * Theo thiết kế promotion_workflow.md - Luồng 3: trả response về cho Order/Cart.
 */
public class CalculateDiscountResponse {

    private BigDecimal originalTotal;
    private BigDecimal totalDiscount;
    private BigDecimal finalTotal;
    private List<AppliedPromotionDto> appliedPromotions;

    public BigDecimal getOriginalTotal() { return originalTotal; }
    public void setOriginalTotal(BigDecimal originalTotal) { this.originalTotal = originalTotal; }
    public BigDecimal getTotalDiscount() { return totalDiscount; }
    public void setTotalDiscount(BigDecimal totalDiscount) { this.totalDiscount = totalDiscount; }
    public BigDecimal getFinalTotal() { return finalTotal; }
    public void setFinalTotal(BigDecimal finalTotal) { this.finalTotal = finalTotal; }
    public List<AppliedPromotionDto> getAppliedPromotions() { return appliedPromotions; }
    public void setAppliedPromotions(List<AppliedPromotionDto> appliedPromotions) { this.appliedPromotions = appliedPromotions; }

    /**
     * Chi tiết 1 promotion đã được áp dụng.
     */
    public static class AppliedPromotionDto {
        private Long promotionId;
        private String code;
        private String discountType;
        private BigDecimal discountValue;
        private BigDecimal discountAmount;
        private BigDecimal platformFunding;
        private BigDecimal sellerFunding;

        public Long getPromotionId() { return promotionId; }
        public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDiscountType() { return discountType; }
        public void setDiscountType(String discountType) { this.discountType = discountType; }
        public BigDecimal getDiscountValue() { return discountValue; }
        public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
        public BigDecimal getPlatformFunding() { return platformFunding; }
        public void setPlatformFunding(BigDecimal platformFunding) { this.platformFunding = platformFunding; }
        public BigDecimal getSellerFunding() { return sellerFunding; }
        public void setSellerFunding(BigDecimal sellerFunding) { this.sellerFunding = sellerFunding; }
    }
}

package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.domain.enums.DiscountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Markdown price application record.
 * Tracks the markdown discount applied to a product/SKU.
 */
public class MarkdownApplied {

    private String id;
    private ProductId productId;
    private String ruleId;
    private SkuId skuId;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal calculatedPrice;
    private LocalDateTime appliedAt;
    private LocalDateTime expiresAt;

    public MarkdownApplied() {}

    public boolean isExpired(Instant now) {
        if (expiresAt == null) return false;
        return expiresAt.atZone(java.time.ZoneOffset.UTC).toInstant().isBefore(now);
    }

    public BigDecimal recalculatePrice(BigDecimal originalPrice) {
        if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Original price must be positive");
        }
        BigDecimal discountAmount;

        if (discountType == DiscountType.PERCENTAGE) {
            discountAmount = originalPrice.multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            if (maxDiscountAmount != null && maxDiscountAmount.compareTo(BigDecimal.ZERO) > 0) {
                discountAmount = discountAmount.min(maxDiscountAmount);
            }
        } else {
            discountAmount = discountValue;
        }

        BigDecimal promotionalPrice = originalPrice.subtract(discountAmount);
        this.calculatedPrice = promotionalPrice.max(BigDecimal.ZERO)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return this.calculatedPrice;
    }

    public String getId() { return id; }
    public ProductId getProductId() { return productId; }
    public String getRuleId() { return ruleId; }
    public SkuId getSkuId() { return skuId; }
    public DiscountType getDiscountType() { return discountType; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public BigDecimal getCalculatedPrice() { return calculatedPrice; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void setId(String id) { this.id = id; }
    public void setProductId(ProductId productId) { this.productId = productId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public void setSkuId(SkuId skuId) { this.skuId = skuId; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }
    public void setCalculatedPrice(BigDecimal calculatedPrice) { this.calculatedPrice = calculatedPrice; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}

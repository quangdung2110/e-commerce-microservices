package com.ecommerce.promotion.presentation.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body cho tạo/cập nhật Promotion (Cart Voucher).
 * Theo thiết kế promotion_db_design.md - Section 1.2
 *
 * Các trường mandatory: ownerType, code, type, discountType, discountValue, startTime, endTime.
 * Optional: campaignId, ownerId, maxDiscountAmount, minPurchaseAmount, conditionsRule, priority, isExclusive.
 */
public class PromotionRequest {

    private Long campaignId;

    @NotNull(message = "Owner type is required (PLATFORM or SELLER)")
    private String ownerType;

    private Long ownerId;

    @NotBlank(message = "Promotion code is required")
    private String code;

    @NotNull(message = "Promotion type is required (UNLIMITED_ALL, LIMITED_FLASH, SELLER_LIMITED)")
    private String type;

    @NotNull(message = "Discount type is required (PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING)")
    private String discountType;

    @NotNull(message = "Discount value is required")
    @DecimalMin(value = "0.01", message = "Discount value must be positive")
    private BigDecimal discountValue;

    private BigDecimal maxDiscountAmount;

    private BigDecimal minPurchaseAmount;

    /**
     * Cây AST Rule Engine (JSON String).
     * Theo thiết kế rule_engine_design.md.
     */
    private String conditionsRule;

    private Integer priority;

    private Boolean isExclusive;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    @NotNull(message = "End time is required")
    private Instant endTime;

    private String status; // ACTIVE, INACTIVE — optional trên update

    // ---- Getters / Setters ----

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }

    public BigDecimal getMinPurchaseAmount() { return minPurchaseAmount; }
    public void setMinPurchaseAmount(BigDecimal minPurchaseAmount) { this.minPurchaseAmount = minPurchaseAmount; }

    public String getConditionsRule() { return conditionsRule; }
    public void setConditionsRule(String conditionsRule) { this.conditionsRule = conditionsRule; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Boolean getIsExclusive() { return isExclusive; }
    public void setIsExclusive(Boolean isExclusive) { this.isExclusive = isExclusive; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

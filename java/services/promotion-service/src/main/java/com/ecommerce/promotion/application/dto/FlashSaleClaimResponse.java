package com.ecommerce.promotion.application.dto;

/**
 * Response cho API Flash Sale Claim.
 * Theo thiết kế promotion_workflow.md - Luồng 2: SPOP Claiming.
 */
public class FlashSaleClaimResponse {

    private Long promotionId;
    private Long claimId;
    private Long userId;
    private boolean success;
    private String message;

    public FlashSaleClaimResponse() {}

    public FlashSaleClaimResponse(Long promotionId, Long claimId, Long userId, boolean success, String message) {
        this.promotionId = promotionId;
        this.claimId = claimId;
        this.userId = userId;
        this.success = success;
        this.message = message;
    }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}

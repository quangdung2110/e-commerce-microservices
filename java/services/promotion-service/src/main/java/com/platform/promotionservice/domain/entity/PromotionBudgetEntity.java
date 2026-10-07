package com.platform.promotionservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Budget cấu hình và counter đã chốt (claimed/used). Không lưu reserved: Redis
 * giữ runtime reservation (ADR-013).
 */
@Entity
@Table(name = "promotion_budgets")
@Getter
@Setter
public class PromotionBudgetEntity {

    @Id
    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "total_claim_quantity")
    private Long totalClaimQuantity;

    @Column(name = "claimed_quantity", nullable = false)
    private long claimedQuantity;

    @Column(name = "total_redemption_quantity")
    private Long totalRedemptionQuantity;

    @Column(name = "used_quantity", nullable = false)
    private long usedQuantity;

    @Column(name = "total_budget_vnd")
    private Long totalBudgetVnd;

    @Column(name = "used_budget_vnd", nullable = false)
    private long usedBudgetVnd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

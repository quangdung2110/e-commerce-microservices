package com.ecommerce.promotion.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Ngân sách & Tồn kho (tách riêng khỏi promotions để tránh Row Lock Contention).
 * Theo thiết kế promotion_db_design.md - Section 1.3
 *
 * Redis Lua Script đóng vai trò "Bảo vệ cửa" (Gatekeeper) cho Budget.
 * Worker chỉ chạy UPDATE đơn giản sau khi Redis đã xác nhận.
 */
@Entity
@Table(name = "promotion_budgets")
public class PromotionBudget {

    @Id
    @Column(name = "promotion_id")
    private Long promotionId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity = 0;

    @Column(name = "used_quantity", nullable = false)
    private Integer usedQuantity = 0;

    @Column(name = "total_budget", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalBudget = BigDecimal.ZERO;

    @Column(name = "used_budget", nullable = false, precision = 15, scale = 2)
    private BigDecimal usedBudget = BigDecimal.ZERO;

    // ---- Constructors ----

    public PromotionBudget() {}

    public PromotionBudget(Promotion promotion, Integer totalQuantity, BigDecimal totalBudget) {
        this.promotion = promotion;
        this.promotionId = promotion.getId();
        this.totalQuantity = totalQuantity;
        this.totalBudget = totalBudget;
    }

    // ---- Getters / Setters ----

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public Promotion getPromotion() { return promotion; }
    public void setPromotion(Promotion promotion) { this.promotion = promotion; }

    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }

    public Integer getUsedQuantity() { return usedQuantity; }
    public void setUsedQuantity(Integer usedQuantity) { this.usedQuantity = usedQuantity; }

    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }

    public BigDecimal getUsedBudget() { return usedBudget; }
    public void setUsedBudget(BigDecimal usedBudget) { this.usedBudget = usedBudget; }
}

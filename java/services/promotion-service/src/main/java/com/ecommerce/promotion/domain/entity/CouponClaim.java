package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.CouponClaimStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Lịch sử Tranh mã Flash Sale (SPOP Claiming).
 * Theo thiết kế promotion_db_design.md - Section 1.4
 *
 * Khi Admin xuất bản Flash Sale: batch-insert 1000 dòng status=AVAILABLE.
 * Redis SPOP bốc claim_id, Worker update owner_id + status=CLAIMED.
 *
 * Chiến lược vòng đời: Hard Deletion theo Domain-Driven Sweeping
 * (Theo thiết kế coupon_claims_deletion_strategy.md)
 */
@Entity
@Table(name = "coupon_claims")
public class CouponClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    // Theo thiết kế: Mặc định NULL, điền user_id khi có người giành được
    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CouponClaimStatus status = CouponClaimStatus.AVAILABLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // ---- Constructors ----

    public CouponClaim() {}

    public CouponClaim(Long promotionId) {
        this.promotionId = promotionId;
        this.status = CouponClaimStatus.AVAILABLE;
    }

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public CouponClaimStatus getStatus() { return status; }
    public void setStatus(CouponClaimStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
}

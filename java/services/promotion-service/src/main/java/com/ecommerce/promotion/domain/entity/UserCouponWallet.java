package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.WalletStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Ví Voucher của User.
 * Theo thiết kế promotion_db_design.md - Section 1.5
 *
 * Quản lý trạng thái 1 suất Khuyến mãi mà User đang sở hữu.
 * Unique Constraint (user_id, promotion_id) — mỗi user chỉ có 1 entry per promotion.
 */
@Entity
@Table(name = "user_coupon_wallets")
public class UserCouponWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WalletStatus status = WalletStatus.SAVED;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @PrePersist
    protected void onCreate() {
        if (this.savedAt == null) {
            this.savedAt = Instant.now();
        }
    }

    // ---- Constructors ----

    public UserCouponWallet() {}

    public UserCouponWallet(Long userId, Long promotionId) {
        this.userId = userId;
        this.promotionId = promotionId;
        this.status = WalletStatus.SAVED;
        this.savedAt = Instant.now();
    }

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public WalletStatus getStatus() { return status; }
    public void setStatus(WalletStatus status) { this.status = status; }

    public Instant getSavedAt() { return savedAt; }
    public void setSavedAt(Instant savedAt) { this.savedAt = savedAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public Instant getExpiredAt() { return expiredAt; }
    public void setExpiredAt(Instant expiredAt) { this.expiredAt = expiredAt; }
}

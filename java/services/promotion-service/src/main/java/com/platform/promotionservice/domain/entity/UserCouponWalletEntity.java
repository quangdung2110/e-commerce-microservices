package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Current ownership/redemption state của voucher, duy nhất theo (user_id,
 * promotion_id). Eligibility chỉ dùng {@code expired_at} đã snapshot lúc save
 * (PROMO-BR-007); campaign end không thu hồi row.
 */
@Entity
@Table(name = "user_coupon_wallets")
@Getter
@Setter
public class UserCouponWalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Column(name = "coupon_claim_id")
    private Long couponClaimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WalletStatus status = WalletStatus.SAVED;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    @Column(name = "discount_amount_vnd")
    private Long discountAmountVnd;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiredAt);
    }

    public boolean isUsableAt(Instant now) {
        return status == WalletStatus.SAVED && !isExpiredAt(now);
    }
}

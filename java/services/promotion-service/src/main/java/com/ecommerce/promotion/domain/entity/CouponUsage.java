package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.UsageStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Sổ cái Phân tán (Ledger & Audit Trail).
 * Theo thiết kế promotion_db_design.md - Section 1.6
 *
 * Bảng quan trọng nhất để đối soát dòng tiền và hoàn mã.
 * Lưu vết áp dụng mã vào Đơn hàng, bao gồm phân bổ chi phí (platform vs seller funding).
 */
@Entity
@Table(name = "coupon_usages")
public class CouponUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    // Theo thiết kế: Số tiền Sàn chịu (VD: Mã giảm 50k, sàn chịu 20k)
    @Column(name = "platform_funding", nullable = false, precision = 10, scale = 2)
    private BigDecimal platformFunding = BigDecimal.ZERO;

    // Theo thiết kế: Số tiền Shop chịu (VD: Mã giảm 50k, shop chịu 30k)
    @Column(name = "seller_funding", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellerFunding = BigDecimal.ZERO;

    // Theo thiết kế: RESERVED, REDEEMED, CANCELLED, REFUNDED
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UsageStatus status = UsageStatus.RESERVED;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    // ---- Constructors ----

    public CouponUsage() {}

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getPlatformFunding() { return platformFunding; }
    public void setPlatformFunding(BigDecimal platformFunding) { this.platformFunding = platformFunding; }

    public BigDecimal getSellerFunding() { return sellerFunding; }
    public void setSellerFunding(BigDecimal sellerFunding) { this.sellerFunding = sellerFunding; }

    public UsageStatus getStatus() { return status; }
    public void setStatus(UsageStatus status) { this.status = status; }

    public Instant getReservedAt() { return reservedAt; }
    public void setReservedAt(Instant reservedAt) { this.reservedAt = reservedAt; }

    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
}

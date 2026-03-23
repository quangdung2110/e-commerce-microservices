package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Luật Giảm giá Gạch chéo (Markdown / Price Slashes).
 * Theo thiết kế promotion_db_design.md - Section 2.1
 *
 * Khác với promotions (Voucher áp ở giỏ hàng), Markdown tác động trực tiếp vào giá hiển thị sản phẩm.
 * ref_id là Polymorphic Foreign Key — ý nghĩa phụ thuộc vào scope_type.
 * Tính toàn vẹn bảo vệ ở tầng Application Logic.
 */
@Entity
@Table(name = "markdown_rules")
public class MarkdownRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 50)
    private OwnerType ownerType;

    @Column(nullable = false, length = 255)
    private String name;

    // Theo thiết kế: PRODUCT, CATEGORY, BRAND, ALL
    // ALL chỉ áp dụng khi owner_type = SELLER
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 50)
    private ScopeType scopeType;

    // Theo thiết kế: Polymorphic Foreign Key — ý nghĩa phụ thuộc scope_type
    @Column(name = "ref_id")
    private Long refId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 50)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MarkdownRuleStatus status = MarkdownRuleStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ---- Constructors ----

    public MarkdownRule() {}

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ScopeType getScopeType() { return scopeType; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public MarkdownRuleStatus getStatus() { return status; }
    public void setStatus(MarkdownRuleStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

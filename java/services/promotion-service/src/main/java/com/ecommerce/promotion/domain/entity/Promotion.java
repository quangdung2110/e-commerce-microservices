package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.*;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mã giảm giá Khuyến mãi (Cart Vouchers).
 * Theo thiết kế promotion_db_design.md - Section 1.2
 *
 * Lưu trữ thông tin cốt lõi, loại khuyến mãi, và Rule Engine bằng JSONB.
 * Cột conditions_rule lưu cây Abstract Syntax Tree (AST).
 */
@Entity
@Table(name = "promotions")
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Theo thiết kế: Có thể NULL do Shop tự tạo voucher lẻ ngoài chiến dịch
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 50)
    private String code;

    // Theo thiết kế: UNLIMITED_ALL, LIMITED_FLASH, SELLER_LIMITED
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PromotionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 50)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    // Theo thiết kế: Giảm tối đa bao nhiêu, áp dụng cho loại PERCENTAGE
    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    // Theo thiết kế: Đưa ra ngoài JSONB để truy vấn nhanh trên giao diện "Gợi ý mã"
    @Column(name = "min_purchase_amount", precision = 10, scale = 2)
    private BigDecimal minPurchaseAmount;

    // Theo thiết kế promotion_db_design.md - Section 3: Cây AST Rules JSONB
    // Theo thiết kế rule_engine_design.md: Custom JSON AST Evaluator
    @Type(JsonType.class)
    @Column(name = "conditions_rule", columnDefinition = "jsonb")
    private String conditionsRule;

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PromotionStatus status = PromotionStatus.ACTIVE;

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

    public Promotion() {}

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public PromotionType getType() { return type; }
    public void setType(PromotionType type) { this.type = type; }

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

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

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public PromotionStatus getStatus() { return status; }
    public void setStatus(PromotionStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

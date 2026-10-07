package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.ValidityMode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Voucher definition (PLATFORM_VOUCHER toàn đơn). Không có start/end/status thời
 * gian riêng; lịch thuộc campaign. Definition bất biến sau publish.
 */
@Entity
@Table(name = "promotions")
@Getter
@Setter
public class PromotionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "distribution_mode", nullable = false, length = 16)
    private DistributionMode distributionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private DiscountType discountType;

    /** basis points (PERCENTAGE) hoặc VND (FIXED_VND). */
    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount_vnd")
    private Long maxDiscountVnd;

    @Column(name = "min_order_amount_vnd", nullable = false)
    private long minOrderAmountVnd;

    /** JSON AST; parse request-scoped, không cache object trong JVM (ADR-002). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions_rule", nullable = false, columnDefinition = "jsonb")
    private String conditionsRule;

    @Column(name = "rule_schema_version", nullable = false)
    private short ruleSchemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_mode", nullable = false, length = 24)
    private ValidityMode validityMode;

    @Column(name = "valid_for_seconds")
    private Long validForSeconds;

    @Column(name = "redeem_until")
    private Instant redeemUntil;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

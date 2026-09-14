package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.InvalidationReason;
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
 * Markdown product-scope, immutable child của campaign. Product tombstone chỉ
 * gắn invalidation overlay (invalidated_at/reason/source_product_revision) mà
 * không sửa definition (PROMO-BR-022).
 */
@Entity
@Table(name = "markdown_rules")
@Getter
@Setter
public class MarkdownRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount_vnd")
    private Long maxDiscountVnd;

    @Column(nullable = false)
    private int priority;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "invalidation_reason", length = 32)
    private InvalidationReason invalidationReason;

    @Column(name = "source_product_revision")
    private Long sourceProductRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isInvalidated() {
        return invalidatedAt != null;
    }
}

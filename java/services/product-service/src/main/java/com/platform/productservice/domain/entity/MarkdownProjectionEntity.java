package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.enums.MarkdownState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection markdown đang hiệu lực của một Product, do Promotion cấp.
 *
 * <p>Row được giữ lại ở {@code INACTIVE} sau khi hết hạn để event cũ replay
 * không kích hoạt lại giá cũ. {@code sourceRevision} là ordering guard duy nhất
 * (PRODUCT-BR-007).
 */
@Entity
@Table(name = "product_markdown_projection")
@Getter
@Setter
@NoArgsConstructor
public class MarkdownProjectionEntity {

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Null khi INACTIVE (tombstone). */
    @Column(name = "promotion_rule_id")
    private Long promotionRuleId;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private MarkdownState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 16)
    private DiscountType discountType;

    @Column(name = "discount_bps")
    private Integer discountBps;

    @Column(name = "fixed_discount_vnd")
    private Long fixedDiscountVnd;

    @Column(name = "max_discount_vnd")
    private Long maxDiscountVnd;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    /** Chỉ có khi INACTIVE: NO_ACTIVE_RULE | EXPIRED | ADMIN_DISABLED. */
    @Column(name = "inactive_reason", length = 32)
    private String inactiveReason;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}

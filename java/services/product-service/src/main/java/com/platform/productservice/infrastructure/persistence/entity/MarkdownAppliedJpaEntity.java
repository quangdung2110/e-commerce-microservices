package com.platform.productservice.infrastructure.persistence.entity;

import com.platform.productservice.domain.enums.DiscountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_markdown_applied",
       uniqueConstraints = @UniqueConstraint(name = "uq_markdown_rule_sku", columnNames = {"rule_id", "sku_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkdownAppliedJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "sku_id")
    private String skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 15, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "calculated_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal calculatedPrice;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}

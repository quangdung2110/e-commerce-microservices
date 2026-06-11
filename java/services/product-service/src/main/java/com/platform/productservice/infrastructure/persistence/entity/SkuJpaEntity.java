package com.platform.productservice.infrastructure.persistence.entity;

import com.platform.productservice.domain.enums.SkuStatus;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "product_skus")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuJpaEntity {

    @Id
    @Column(name = "sku_id")
    private String skuId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "sku_code", nullable = false, unique = true)
    private String skuCode;

    @Column(name = "original_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "promotional_price", precision = 15, scale = 2)
    private BigDecimal promotionalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SkuStatus status;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "display_quantity", nullable = false)
    private Integer displayQuantity = 0;

    @Column(name = "last_inventory_sync_at", nullable = false)
    private Long lastInventorySyncAt = 0L;

    @Type(JsonType.class)
    @Column(name = "variant_attributes_json", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> variantAttributesJson = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private ProductJpaEntity product;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

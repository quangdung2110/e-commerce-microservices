package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.SkuStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Biến thể bán được. Admin chỉ đặt {@code basePriceVnd}; {@code sellingPriceVnd}
 * do Product tính từ markdown projection (PRODUCT-BR-005). Không có cột tồn kho.
 */
@Entity
@Table(name = "product_skus")
@Getter
@Setter
@NoArgsConstructor
public class SkuEntity {

    @Id
    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku_code", nullable = false, length = 64)
    private String skuCode;

    @Column(name = "base_price_vnd", nullable = false)
    private long basePriceVnd;

    @Column(name = "selling_price_vnd", nullable = false)
    private long sellingPriceVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SkuStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variant_attributes_json", nullable = false)
    private Map<String, Object> variantAttributes = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

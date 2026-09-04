package com.platform.productservice.domain.enums;

/**
 * Lifecycle của Product. Chỉ admin đổi trạng thái; không có transition tự động
 * theo tồn kho (docs/product/domain.md, PRODUCT-BR-009).
 */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    HIDDEN
}

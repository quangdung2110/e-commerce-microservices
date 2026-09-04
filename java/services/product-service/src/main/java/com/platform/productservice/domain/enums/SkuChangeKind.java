package com.platform.productservice.domain.enums;

/**
 * data.change_kind của {@code com.ecommerce.catalog.sku-upserted.v1}
 * (contracts/events/product/v1/schemas/sku-upserted.schema.json).
 */
public enum SkuChangeKind {
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_STATUS_CHANGED,
    SKU_CREATED,
    SKU_UPDATED,
    MARKDOWN_REPRICED,
    MARKDOWN_EXPIRED
}

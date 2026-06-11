package com.platform.productservice.domain.enums;

/**
 * Specific fields that changed in a product/SKU, used for change tracking.
 */
public enum ChangedField {
    original_price,
    promotional_price,
    best_price,
    has_promotion,
    sku_status,
    variant_attributes,
    media,
    product_status,
    display_quantity
}

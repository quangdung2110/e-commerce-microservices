package com.platform.productservice.domain.enums;

/**
 * Source that triggered a product/SKU change.
 * Used for tracking change origin and triggering appropriate side effects.
 */
public enum ChangeSource {
    ADMIN_SET_INACTIVE,
    ADMIN_SET_ACTIVE,
    ADMIN_UPDATE,
    AUTO_HIDE_OUT_OF_STOCK,
    AUTO_REACTIVATE_IN_STOCK,
    MARKDOWN_CREATED,
    MARKDOWN_UPDATED,
    MARKDOWN_ENDED,
    INVENTORY_SYNC_PHASE2
}

package com.platform.productservice.domain.enums;

/**
 * SKU availability status.
 */
public enum SkuStatus {
    ACTIVE,
    INACTIVE,
    OUT_OF_STOCK;

    public boolean isAvailable() {
        return this == ACTIVE;
    }
}

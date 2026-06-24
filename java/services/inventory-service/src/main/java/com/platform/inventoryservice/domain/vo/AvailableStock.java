package com.platform.inventoryservice.domain.vo;

/**
 * Per-warehouse available quantity đọc từ {@code physical_inventory}.
 * Aggregate (sum across warehouses) tính ở routing layer.
 */
public record AvailableStock(String skuId, String productId, long warehouseId, long quantity) {

    public AvailableStock {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
    }

    public boolean canFulfill(long requested) {
        return quantity >= requested;
    }
}
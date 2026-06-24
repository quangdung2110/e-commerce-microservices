package com.platform.inventoryservice.domain.vo;

/**
 * Composite identity cho {@code (sku_id, warehouse_id)}.
 * Dùng cho mọi Redis key construction (hash-tag invariant).
 */
public record SkuWarehouseKey(String skuId, long warehouseId) {

    public SkuWarehouseKey {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (warehouseId <= 0) {
            throw new IllegalArgumentException("warehouseId must be positive");
        }
    }

    /**
     * Hash-tag thống nhất cho Redis Cluster — dùng cho mọi key construction.
     * Format: {@code "{<skuId>:<warehouseId>}"}.
     */
    public String hashTag() {
        return "{" + skuId + ":" + warehouseId + "}";
    }
}
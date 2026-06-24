package com.platform.inventoryservice.domain.event;

/**
 * Payload cho event {@code INVENTORY_STOCK_CHANGED} (per-SKU aggregate, cold path).
 * {@code availableQuantity} là tổng khả dụng per-SKU (sum từ {@code physical_inventory}).
 */
public record StockChangedPayload(
        String productId,
        String skuId,
        long availableQuantity,
        String changeType) {
}
package com.platform.inventoryservice.domain.event;

/**
 * Payload cho event {@code INVENTORY_STOCK_MUTATION} (per-warehouse, hot path).
 * Phát ra từ Lua script → Relay Worker → Kafka topic {@code t.inventory.events}.
 */
public record StockMutationPayload(
        String skuId,
        String productId,
        long warehouseId,
        String changeType,
        int quantity,
        long availableAfter,
        long reservedAfter,
        long orderId,
        long orderItemId,
        String correlationId) {}
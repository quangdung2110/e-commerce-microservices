package com.platform.inventoryservice.port.outbound.cache;

public interface IdempotencyKeyBuilder {
    String build(String op, String skuId, long warehouseId, long orderId);
}

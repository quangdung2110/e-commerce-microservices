package com.platform.inventoryservice.temporal.command;

public record ReserveCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

package com.platform.inventoryservice.temporal.command;

public record RollbackCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

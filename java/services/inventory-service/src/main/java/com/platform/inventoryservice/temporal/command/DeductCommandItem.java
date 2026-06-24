package com.platform.inventoryservice.temporal.command;

public record DeductCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

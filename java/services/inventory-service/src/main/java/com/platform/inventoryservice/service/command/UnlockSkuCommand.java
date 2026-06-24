package com.platform.inventoryservice.service.command;

public record UnlockSkuCommand(
    String skuId,
    long warehouseId,
    String reason,
    String correlationId
) {
    public UnlockSkuCommand {
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason required");
    }
}

package com.platform.inventoryservice.service.result;

public record StockSnapshot(int actualQuantity, int reservedQuantity, int availableQuantity) {}

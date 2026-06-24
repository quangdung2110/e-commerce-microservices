package com.platform.inventoryservice.service.command;

import com.platform.inventoryservice.domain.enums.ReferenceType;

public record RestockCommand(
    long warehouseId,
    String skuId,
    String productId,
    int quantity,
    ReferenceType referenceType,
    Long orderId,
    Long orderItemId,
    String refundRequestId,
    String correlationId
) {
    public RestockCommand {
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");

        if (referenceType == ReferenceType.ORDER_RETURNED_RESTOCK) {
            if (orderId == null || orderItemId == null || refundRequestId == null) {
                throw new IllegalArgumentException(
                    "orderId, orderItemId, refundRequestId required for ORDER_RETURNED_RESTOCK");
            }
        } else if (referenceType != ReferenceType.ADMIN_RESTOCK) {
            throw new IllegalArgumentException("Unsupported referenceType: " + referenceType);
        }
    }
}

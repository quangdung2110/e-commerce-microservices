package com.platform.inventoryservice.dto.response;

import com.platform.inventoryservice.domain.enums.ReferenceType;
import com.platform.inventoryservice.service.result.RestockResult;

import java.time.Instant;
import java.util.UUID;

public record RestockResponse(
    boolean success,
    long warehouseId,
    String skuId,
    String productId,
    ReferenceType referenceType,
    int quantityAdded,
    PhysicalSnapshot physical,
    RefundSnapshot refund,
    UUID eventId,
    Instant timestamp
) {
    public record PhysicalSnapshot(int actualQuantity, int reservedQuantity, int availableQuantity) {}
    
    public record RefundSnapshot(
        long orderId, 
        long orderItemId, 
        String refundRequestId,
        Instant refundReturnedAt, 
        boolean allItemsReceived
    ) {}

    public static RestockResponse from(RestockResult r) {
        return new RestockResponse(
            true,
            r.warehouseId(), r.skuId(), r.productId(),
            r.referenceType(), r.quantityAdded(),
            new PhysicalSnapshot(
                r.physical().actualQuantity(),
                r.physical().reservedQuantity(),
                r.physical().availableQuantity()),
            r.refund() == null ? null : new RefundSnapshot(
                r.refund().orderId(), r.refund().orderItemId(),
                r.refund().refundRequestId(), r.refund().refundReturnedAt(),
                r.refund().allItemsReceived()),
            r.eventId(), r.timestamp()
        );
    }
}

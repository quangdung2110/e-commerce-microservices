package com.platform.inventoryservice.dto.request;

import com.platform.inventoryservice.domain.enums.ReferenceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RestockRequest(
    @Min(1) long warehouseId,
    @NotBlank @Size(max = 64) String skuId,
    @NotBlank @Size(max = 64) String productId,
    @Min(1) @Max(1_000_000) int quantity,
    ReferenceType referenceType,

    Long orderId,
    Long orderItemId,
    String refundRequestId
) {}

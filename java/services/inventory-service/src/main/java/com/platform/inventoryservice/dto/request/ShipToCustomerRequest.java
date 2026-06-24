package com.platform.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ShipToCustomerRequest(
    @Min(1) long orderId,
    @Min(1) long warehouseId,
    @NotNull Instant shippedAt,
    @Size(max = 512) String courierNote
) {}

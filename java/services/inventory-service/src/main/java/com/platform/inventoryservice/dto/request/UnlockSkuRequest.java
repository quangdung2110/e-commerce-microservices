package com.platform.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnlockSkuRequest(
    @NotBlank @Size(max = 64) String skuId,
    @Min(1) long warehouseId,
    @NotBlank @Size(max = 512) String reason
) {}

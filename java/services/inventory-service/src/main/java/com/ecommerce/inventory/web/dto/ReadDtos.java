package com.ecommerce.inventory.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Wire shapes of contracts/http/inventory/read/v1/openapi.yaml. */
public final class ReadDtos {

    private ReadDtos() {
    }

    public record BatchSkuAvailabilityRequest(
            @NotNull @Size(min = 1, max = 500) List<@NotNull @Size(min = 1, max = 128) String> skuIds) {
    }

    /** {@code availability_revision} and {@code evaluated_at} are explicit nulls for UNKNOWN. */
    public record SkuAvailabilityResult(String skuId, String status, Long availabilityRevision, Instant evaluatedAt) {
    }

    public record BatchSkuAvailabilityResponse(List<SkuAvailabilityResult> items) {
    }
}

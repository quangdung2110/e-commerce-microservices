package com.platform.inventoryservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoutingOptionsRequest(
    @NotEmpty(message = "items required")
    @Size(max = 100, message = "items max 100")
    @Valid List<@NotNull RoutingItemDto> items,

    @NotNull(message = "customer_location required")
    @Valid CustomerLocationDto customerLocation
) {
    public record RoutingItemDto(
        @NotBlank @Size(max = 64) String skuId,
        @Min(1) @Max(10000) int quantity
    ) {}

    public record CustomerLocationDto(
        @NotBlank @Size(max = 20) String cityCode,
        @Size(max = 20) String districtCode
    ) {}
}

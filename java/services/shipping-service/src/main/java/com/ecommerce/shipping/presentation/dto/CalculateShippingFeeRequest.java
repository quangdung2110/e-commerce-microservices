package com.ecommerce.shipping.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: request payload cho calculate-batch endpoint.
public record CalculateShippingFeeRequest(
        @NotEmpty List<@Valid QuotePayload> quotes
) {

    public record QuotePayload(
            @NotNull Integer fromWarehouseId,
            @NotNull String fromProvince,
            String fromDistrict,
            @NotNull String toProvince,
            String toDistrict,
            String toWard,
            @NotNull Integer weightGrams,
            @Valid Dimensions dimensions,
            BigDecimal codAmount,
            Integer itemsCount
    ) {
    }

    public record Dimensions(
            Integer length,
            Integer width,
            Integer height
    ) {
    }
}

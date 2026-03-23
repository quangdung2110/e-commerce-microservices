package com.ecommerce.shipping.application.dto;

import java.math.BigDecimal;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: request calculate-batch nhiều quotes.
public record CalculateShippingFeeCommand(
        List<QuoteRequest> quotes
) {

    public record QuoteRequest(
            Integer fromWarehouseId,
            String fromProvinceCode,
            String fromDistrictCode,
            String toProvinceCode,
            String toDistrictCode,
            String toWardCode,
            Integer weightGrams,
            Integer lengthCm,
            Integer widthCm,
            Integer heightCm,
            BigDecimal codAmount,
            Integer itemsCount
    ) {
    }
}

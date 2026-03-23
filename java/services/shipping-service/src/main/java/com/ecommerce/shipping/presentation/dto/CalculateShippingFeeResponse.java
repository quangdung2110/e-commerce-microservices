package com.ecommerce.shipping.presentation.dto;

import java.math.BigDecimal;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: response options theo từng quote.
public record CalculateShippingFeeResponse(
        List<QuoteOptionsResponse> quotes
) {

    public record QuoteOptionsResponse(
            Integer quoteIndex,
            List<ShippingOptionResponse> options
    ) {
    }

    public record ShippingOptionResponse(
            String provider,
            String service,
            BigDecimal fee,
            Integer deliveryDays
    ) {
    }
}

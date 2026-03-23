package com.ecommerce.shipping.application.dto;

import java.math.BigDecimal;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: response gồm options theo từng quote.
public record CalculateShippingFeeResult(
        List<QuoteOptions> quotes
) {

    public record QuoteOptions(
            Integer quoteIndex,
            List<ShippingOption> options
    ) {
    }

    public record ShippingOption(
            String provider,
            String service,
            BigDecimal fee,
            Integer deliveryDays
    ) {
    }
}

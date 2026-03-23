package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;

import java.math.BigDecimal;

// Theo thiết kế shipping_workflow.md mục 1: surcharge = dimension + cod + remote area.
public interface ShippingSurchargePort {

    SurchargeBreakdown resolve(String providerCode, CalculateShippingFeeCommand.QuoteRequest quoteRequest);

    record SurchargeBreakdown(
            BigDecimal dimensionSurcharge,
            BigDecimal codFee,
            BigDecimal remoteAreaFee
    ) {
    }
}

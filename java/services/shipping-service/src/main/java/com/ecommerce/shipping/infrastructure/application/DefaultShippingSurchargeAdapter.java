package com.ecommerce.shipping.infrastructure.application;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;
import com.ecommerce.shipping.application.port.out.ShippingSurchargePort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// Theo thiết kế shipping_workflow.md mục 1: trả breakdown surcharge (dimension/cod/remote) cho fee formula.
@Component
public class DefaultShippingSurchargeAdapter implements ShippingSurchargePort {

    @Override
    public SurchargeBreakdown resolve(String providerCode, CalculateShippingFeeCommand.QuoteRequest quoteRequest) {
        BigDecimal dimensionSurcharge = BigDecimal.ZERO;
        BigDecimal codFee = quoteRequest.codAmount() != null && quoteRequest.codAmount().compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.valueOf(5000)
                : BigDecimal.ZERO;
        BigDecimal remoteAreaFee = BigDecimal.ZERO;

        return new SurchargeBreakdown(dimensionSurcharge, codFee, remoteAreaFee);
    }
}

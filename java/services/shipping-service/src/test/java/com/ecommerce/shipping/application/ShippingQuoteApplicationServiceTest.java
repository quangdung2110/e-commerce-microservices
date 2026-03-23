package com.ecommerce.shipping.application;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;
import com.ecommerce.shipping.application.port.out.RateCardRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.application.port.out.ShippingSurchargePort;
import com.ecommerce.shipping.application.service.ShippingQuoteApplicationService;
import com.ecommerce.shipping.domain.model.RateCard;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import com.ecommerce.shipping.domain.service.ShippingFeeCalculatorDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingQuoteApplicationServiceTest {

    @Mock
    private ShippingProviderCatalogPort shippingProviderCatalogPort;

    @Mock
    private RateCardRepositoryPort rateCardRepositoryPort;

    @Mock
    private ShippingSurchargePort shippingSurchargePort;

    private ShippingQuoteApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ShippingQuoteApplicationService(
                shippingProviderCatalogPort,
                rateCardRepositoryPort,
                shippingSurchargePort,
                new ShippingFeeCalculatorDomainService());
    }

    @Test
    void shouldReturnProviderOptionsForEachQuote() {
        // Theo thiết kế shipping_workflow.md mục 1: batch quotes + multi-provider options
        ShippingProvider ghn = new ShippingProvider(1, "GHN", "Giao Hang Nhanh", true, 1);
        when(shippingProviderCatalogPort.findActiveProviders()).thenReturn(List.of(ghn));

        RateCard rateCard = new RateCard(
                1L,
                1,
                "HCM",
                null,
                "HCM",
                null,
                null,
                500,
                BigDecimal.valueOf(25000),
                500,
                BigDecimal.valueOf(5000),
                2);
        when(rateCardRepositoryPort.findActiveRateCardsByRouteWithFallback(
                eq(1), eq("HCM"), any(), eq("HCM"), any(), any()))
                .thenReturn(List.of(rateCard));

        when(shippingSurchargePort.resolve(eq("GHN"), any()))
                .thenReturn(new ShippingSurchargePort.SurchargeBreakdown(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));

        CalculateShippingFeeCommand command = new CalculateShippingFeeCommand(List.of(
                new CalculateShippingFeeCommand.QuoteRequest(
                        5,
                        "HCM",
                        null,
                        "HCM",
                        null,
                        null,
                        500,
                        20,
                        15,
                        10,
                        BigDecimal.ZERO,
                        2)));

        var result = service.calculateBatch(command);

        assertEquals(1, result.quotes().size());
        assertEquals(1, result.quotes().getFirst().options().size());
        assertEquals("GHN", result.quotes().getFirst().options().getFirst().provider());
        assertEquals(BigDecimal.valueOf(25000).setScale(2), result.quotes().getFirst().options().getFirst().fee());
    }
}

package com.ecommerce.shipping.application.service;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;
import com.ecommerce.shipping.application.dto.CalculateShippingFeeResult;
import com.ecommerce.shipping.application.port.in.CalculateShippingFeeUseCase;
import com.ecommerce.shipping.application.port.out.RateCardRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.application.port.out.ShippingSurchargePort;
import com.ecommerce.shipping.domain.model.RateCard;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import com.ecommerce.shipping.domain.service.ShippingFeeCalculatorDomainService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: quote calculation application service.
@Service
public class ShippingQuoteApplicationService implements CalculateShippingFeeUseCase {

	private static final String DEFAULT_SERVICE_TYPE = "STANDARD";

	private final ShippingProviderCatalogPort shippingProviderCatalogPort;
	private final RateCardRepositoryPort rateCardRepositoryPort;
	private final ShippingSurchargePort shippingSurchargePort;
	private final ShippingFeeCalculatorDomainService shippingFeeCalculatorDomainService;

	public ShippingQuoteApplicationService(ShippingProviderCatalogPort shippingProviderCatalogPort,
										   RateCardRepositoryPort rateCardRepositoryPort,
										   ShippingSurchargePort shippingSurchargePort,
										   ShippingFeeCalculatorDomainService shippingFeeCalculatorDomainService) {
		this.shippingProviderCatalogPort = shippingProviderCatalogPort;
		this.rateCardRepositoryPort = rateCardRepositoryPort;
		this.shippingSurchargePort = shippingSurchargePort;
		this.shippingFeeCalculatorDomainService = shippingFeeCalculatorDomainService;
	}

	@Override
	public CalculateShippingFeeResult calculateBatch(CalculateShippingFeeCommand command) {
		if (command == null || command.quotes() == null || command.quotes().isEmpty()) {
			throw new IllegalArgumentException("quotes must not be empty");
		}

		List<ShippingProvider> providers = shippingProviderCatalogPort.findActiveProviders().stream()
				.sorted(Comparator.comparing(ShippingProvider::priority))
				.toList();

		List<CalculateShippingFeeResult.QuoteOptions> quoteOptions = new ArrayList<>();
		for (int quoteIndex = 0; quoteIndex < command.quotes().size(); quoteIndex++) {
			CalculateShippingFeeCommand.QuoteRequest quote = command.quotes().get(quoteIndex);
			List<CalculateShippingFeeResult.ShippingOption> options = new ArrayList<>();

			for (ShippingProvider provider : providers) {
				List<RateCard> matchedRateCards = rateCardRepositoryPort.findActiveRateCardsByRouteWithFallback(
						provider.id(),
						quote.fromProvinceCode(),
						quote.fromDistrictCode(),
						quote.toProvinceCode(),
						quote.toDistrictCode(),
						quote.toWardCode());
				if (matchedRateCards.isEmpty()) {
					continue;
				}

				RateCard selectedRateCard = matchedRateCards.getFirst();
				ShippingSurchargePort.SurchargeBreakdown surchargeBreakdown =
						shippingSurchargePort.resolve(provider.code(), quote);

				BigDecimal fee = shippingFeeCalculatorDomainService.calculateFee(
						selectedRateCard,
						quote.weightGrams(),
						surchargeBreakdown.dimensionSurcharge(),
						surchargeBreakdown.codFee(),
						surchargeBreakdown.remoteAreaFee());

				options.add(new CalculateShippingFeeResult.ShippingOption(
						provider.code(),
						DEFAULT_SERVICE_TYPE,
						fee,
						selectedRateCard.estimatedDays()));
			}

			quoteOptions.add(new CalculateShippingFeeResult.QuoteOptions(quoteIndex, options));
		}

		return new CalculateShippingFeeResult(quoteOptions);
	}
}

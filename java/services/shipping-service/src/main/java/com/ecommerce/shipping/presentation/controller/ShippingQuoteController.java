package com.ecommerce.shipping.presentation.controller;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;
import com.ecommerce.shipping.application.dto.CalculateShippingFeeResult;
import com.ecommerce.shipping.application.port.in.CalculateShippingFeeUseCase;
import com.ecommerce.shipping.presentation.dto.CalculateShippingFeeRequest;
import com.ecommerce.shipping.presentation.dto.CalculateShippingFeeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: /v1/shipping/calculate-batch.
@RestController
@RequestMapping("/v1/shipping")
public class ShippingQuoteController {

    private final CalculateShippingFeeUseCase calculateShippingFeeUseCase;

    public ShippingQuoteController(CalculateShippingFeeUseCase calculateShippingFeeUseCase) {
	this.calculateShippingFeeUseCase = calculateShippingFeeUseCase;
    }

    @PostMapping("/calculate-batch")
    public CalculateShippingFeeResponse calculateBatch(@Valid @RequestBody CalculateShippingFeeRequest request) {
	CalculateShippingFeeCommand command = new CalculateShippingFeeCommand(request.quotes().stream()
		.map(quote -> new CalculateShippingFeeCommand.QuoteRequest(
			quote.fromWarehouseId(),
			quote.fromProvince(),
			quote.fromDistrict(),
			quote.toProvince(),
			quote.toDistrict(),
			quote.toWard(),
			quote.weightGrams(),
			quote.dimensions() == null ? null : quote.dimensions().length(),
			quote.dimensions() == null ? null : quote.dimensions().width(),
			quote.dimensions() == null ? null : quote.dimensions().height(),
			quote.codAmount(),
			quote.itemsCount()))
		.toList());

	CalculateShippingFeeResult result = calculateShippingFeeUseCase.calculateBatch(command);
	List<CalculateShippingFeeResponse.QuoteOptionsResponse> quotes = result.quotes().stream()
		.map(item -> new CalculateShippingFeeResponse.QuoteOptionsResponse(
			item.quoteIndex(),
			item.options().stream()
				.map(option -> new CalculateShippingFeeResponse.ShippingOptionResponse(
					option.provider(),
					option.service(),
					option.fee(),
					option.deliveryDays()))
				.toList()))
		.toList();

	return new CalculateShippingFeeResponse(quotes);
    }
}

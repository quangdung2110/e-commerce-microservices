package com.ecommerce.shipping.domain.service;

import com.ecommerce.shipping.domain.model.RateCard;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Theo thiết kế shipping_workflow.md mục 1: batch fee calculation engine.
public class ShippingFeeCalculatorDomainService {

	public BigDecimal calculateFee(RateCard rateCard,
								   int totalWeightGrams,
								   BigDecimal dimensionSurcharge,
								   BigDecimal codFee,
								   BigDecimal remoteAreaFee) {
		if (rateCard == null) {
			throw new IllegalArgumentException("rateCard must not be null");
		}
		if (totalWeightGrams <= 0) {
			throw new IllegalArgumentException("totalWeightGrams must be positive");
		}

		BigDecimal baseFee = rateCard.baseFee();
		if (totalWeightGrams <= rateCard.baseWeight()) {
			return baseFee
					.add(defaultZero(dimensionSurcharge))
					.add(defaultZero(codFee))
					.add(defaultZero(remoteAreaFee));
		}

		int excessWeight = totalWeightGrams - rateCard.baseWeight();
		int stepWeight = Math.max(rateCard.stepWeight(), 1);
		int stepCount = (int) Math.ceil((double) excessWeight / stepWeight);

		BigDecimal variableFee = rateCard.stepFee().multiply(BigDecimal.valueOf(stepCount));
		return baseFee
				.add(variableFee)
				.add(defaultZero(dimensionSurcharge))
				.add(defaultZero(codFee))
				.add(defaultZero(remoteAreaFee))
				.setScale(2, RoundingMode.HALF_UP);
	}

	private BigDecimal defaultZero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}
}

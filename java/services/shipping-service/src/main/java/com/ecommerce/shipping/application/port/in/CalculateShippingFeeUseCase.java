package com.ecommerce.shipping.application.port.in;

import com.ecommerce.shipping.application.dto.CalculateShippingFeeCommand;
import com.ecommerce.shipping.application.dto.CalculateShippingFeeResult;

// Theo thiết kế shipping_workflow.md mục 1: POST /v1/shipping/calculate-batch.
public interface CalculateShippingFeeUseCase {

	CalculateShippingFeeResult calculateBatch(CalculateShippingFeeCommand command);
}

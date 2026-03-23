package com.ecommerce.shipping.application.port.in;

import com.ecommerce.shipping.application.dto.CancelShipmentCommand;

// Theo thiết kế shipping_workflow.md mục 3: CancelShippingActivity.
public interface CancelShipmentUseCase {

	void cancelShipment(CancelShipmentCommand command);
}

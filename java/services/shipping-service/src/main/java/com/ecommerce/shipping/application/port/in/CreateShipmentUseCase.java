package com.ecommerce.shipping.application.port.in;

import com.ecommerce.shipping.application.dto.CreateShipmentCommand;
import com.ecommerce.shipping.application.dto.CreateShipmentResult;

// Theo thiết kế shipping_workflow.md mục 2: CreateShippingLabelActivity.
public interface CreateShipmentUseCase {

	CreateShipmentResult createShipment(CreateShipmentCommand command);
}

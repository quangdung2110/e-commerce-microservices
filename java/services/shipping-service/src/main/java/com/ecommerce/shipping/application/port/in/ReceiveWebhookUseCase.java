package com.ecommerce.shipping.application.port.in;

import com.ecommerce.shipping.application.dto.ReceiveWebhookCommand;

// Theo thiết kế shipping_workflow.md mục 5: webhook ingest endpoint.
public interface ReceiveWebhookUseCase {

	void receiveWebhook(ReceiveWebhookCommand command);
}

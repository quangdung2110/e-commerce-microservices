package com.ecommerce.shipping.application.service;

import com.ecommerce.shipping.application.dto.ReceiveWebhookCommand;
import com.ecommerce.shipping.application.port.in.ReceiveWebhookUseCase;
import com.ecommerce.shipping.application.port.out.RawWebhookPublisherPort;
import org.springframework.stereotype.Service;

// Theo thiết kế shipping_workflow.md mục 5: webhook endpoint chỉ dump Kafka raw.
@Service
public class WebhookIngestionApplicationService implements ReceiveWebhookUseCase {

	private final RawWebhookPublisherPort rawWebhookPublisherPort;

	public WebhookIngestionApplicationService(RawWebhookPublisherPort rawWebhookPublisherPort) {
		this.rawWebhookPublisherPort = rawWebhookPublisherPort;
	}

	@Override
	public void receiveWebhook(ReceiveWebhookCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("receiveWebhook command must not be null");
		}
		if (command.provider() == null || command.provider().isBlank()) {
			throw new IllegalArgumentException("provider must not be blank");
		}
		if (command.rawBody() == null || command.rawBody().isBlank()) {
			throw new IllegalArgumentException("rawBody must not be blank");
		}

		rawWebhookPublisherPort.publishRawWebhook(command);
	}
}

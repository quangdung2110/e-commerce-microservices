package com.ecommerce.shipping.presentation.controller;

import com.ecommerce.shipping.application.dto.ReceiveWebhookCommand;
import com.ecommerce.shipping.application.port.in.ReceiveWebhookUseCase;
import com.ecommerce.shipping.presentation.dto.WebhookAcceptedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 5: webhook ingest endpoint theo provider.
@RestController
@RequestMapping("/v1/shipping/webhook")
public class ShippingWebhookController {

	private final ReceiveWebhookUseCase receiveWebhookUseCase;

	public ShippingWebhookController(ReceiveWebhookUseCase receiveWebhookUseCase) {
		this.receiveWebhookUseCase = receiveWebhookUseCase;
	}

	@PostMapping("/{provider}")
	public ResponseEntity<WebhookAcceptedResponse> receiveWebhook(@PathVariable String provider,
																  @RequestBody String rawBody,
																  org.springframework.http.HttpHeaders headers) {
		Map<String, String> flattenedHeaders = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			flattenedHeaders.put(entry.getKey(), String.join(",", entry.getValue()));
		}

		receiveWebhookUseCase.receiveWebhook(new ReceiveWebhookCommand(
				provider,
				headers.getFirst("x-tracking-number"),
				rawBody,
				flattenedHeaders));
		return ResponseEntity.ok(new WebhookAcceptedResponse("accepted"));
	}
}

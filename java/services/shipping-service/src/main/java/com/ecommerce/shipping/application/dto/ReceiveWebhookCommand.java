package com.ecommerce.shipping.application.dto;

import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 5: webhook raw payload ingest command.
public record ReceiveWebhookCommand(
        String provider,
        String trackingNumber,
        String rawBody,
        Map<String, String> headers
) {
}

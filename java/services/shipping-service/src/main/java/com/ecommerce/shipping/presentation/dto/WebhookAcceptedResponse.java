package com.ecommerce.shipping.presentation.dto;

// Theo thiết kế shipping_workflow.md mục 5: webhook endpoint trả 200 ngay sau khi enqueue raw payload.
public record WebhookAcceptedResponse(
        String status
) {
}

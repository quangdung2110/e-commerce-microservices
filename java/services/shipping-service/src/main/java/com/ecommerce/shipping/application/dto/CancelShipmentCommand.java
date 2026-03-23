package com.ecommerce.shipping.application.dto;

// Theo thiết kế shipping_workflow.md mục 3: cancel shipment compensation request.
public record CancelShipmentCommand(
        Long shipmentId,
        String reason
) {
}

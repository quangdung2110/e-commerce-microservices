package com.ecommerce.shipping.application.dto;

import java.time.LocalDate;

// Theo thiết kế shipping_workflow.md mục 2: create shipment response.
public record CreateShipmentResult(
        Long shipmentId,
        String trackingNumber,
        String labelUrl,
        LocalDate estimatedDeliveryDate
) {
}

package com.ecommerce.shipping.presentation.dto;

import java.time.LocalDate;

// Theo thiết kế shipping_workflow.md mục 2: response tạo vận đơn.
public record CreateShipmentResponse(
        Long shipmentId,
        String trackingNumber,
        String labelUrl,
        LocalDate estimatedDeliveryDate
) {
}

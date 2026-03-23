package com.ecommerce.shipping.presentation.dto;

import jakarta.validation.constraints.NotBlank;

// Theo thiết kế shipping_workflow.md mục 3: request hủy shipment theo lý do compensation.
public record CancelShipmentRequest(
        @NotBlank String reason
) {
}

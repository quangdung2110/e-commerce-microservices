package com.ecommerce.shipping.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 2: request tạo shipment từ Order/Temporal.
public record CreateShipmentRequest(
        @NotNull Long orderId,
        @NotNull Long parcelId,
        @NotBlank String courierCode,
        @NotBlank String serviceType,
        @NotNull Map<String, Object> sender,
        @NotNull Map<String, Object> receiver,
        @NotNull List<Map<String, Object>> items,
        BigDecimal codAmount,
        @NotNull Integer totalWeightGrams,
        String note
) {
}

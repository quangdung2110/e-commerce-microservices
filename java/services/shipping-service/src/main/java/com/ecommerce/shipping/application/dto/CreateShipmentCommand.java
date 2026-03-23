package com.ecommerce.shipping.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 2: create shipment request từ Temporal Activity.
public record CreateShipmentCommand(
        Long orderId,
        Long parcelId,
        String courierCode,
        String serviceType,
        Map<String, Object> sender,
        Map<String, Object> receiver,
        List<Map<String, Object>> items,
        BigDecimal codAmount,
        Integer totalWeightGrams,
        String note
) {
}

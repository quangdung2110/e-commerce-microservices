package com.ecommerce.shipping.domain.model;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

// Theo thiết kế shipping_db_design.md mục 2.1: aggregate shipments.
public record Shipment(
        Long id,
        Long parcelId,
        Long orderId,
        Integer providerId,
        String trackingNumber,
        Map<String, Object> senderInfo,
        Map<String, Object> receiverInfo,
        Map<String, Object> packageInfo,
        ShipmentStatus standardizedStatus,
        String providerStatus,
        BigDecimal shippingFee,
        BigDecimal codAmount,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

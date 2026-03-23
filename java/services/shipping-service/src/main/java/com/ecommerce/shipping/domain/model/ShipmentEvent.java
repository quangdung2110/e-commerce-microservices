package com.ecommerce.shipping.domain.model;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;

import java.time.Instant;

// Theo thiết kế shipping_db_design.md mục 2.2 và shipping_workflow.md mục 4: timeline event model.
public record ShipmentEvent(
        Long shipmentId,
        Instant occurredAt,
        ShipmentStatus standardizedStatus,
        String providerStatus,
        String description,
        String location,
        String rawPayload
) {
}

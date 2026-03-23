package com.ecommerce.shipping.application.dto;

import java.time.Instant;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 4: timeline tracking response.
public record TrackingEventsResult(
        String trackingNumber,
        String courierCode,
        String currentStatus,
        List<TrackingEventItem> events
) {

    public record TrackingEventItem(
            String status,
            String location,
            Instant timestamp
    ) {
    }
}

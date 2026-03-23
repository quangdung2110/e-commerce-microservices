package com.ecommerce.shipping.presentation.dto;

import java.time.Instant;
import java.util.List;

// Theo thiết kế shipping_workflow.md mục 4: response timeline tracking events.
public record TrackingEventsResponse(
        String trackingNumber,
        String courierCode,
        String currentStatus,
        List<TrackingEventResponse> events
) {

    public record TrackingEventResponse(
            String status,
            String location,
            Instant timestamp
    ) {
    }
}

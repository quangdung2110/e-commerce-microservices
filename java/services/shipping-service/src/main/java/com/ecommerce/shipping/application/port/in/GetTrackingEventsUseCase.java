package com.ecommerce.shipping.application.port.in;

import com.ecommerce.shipping.application.dto.TrackingEventsResult;

// Theo thiết kế shipping_workflow.md mục 4: GET tracking events từ ScyllaDB.
public interface GetTrackingEventsUseCase {

	TrackingEventsResult getTrackingEvents(String trackingNumber, int limit);
}

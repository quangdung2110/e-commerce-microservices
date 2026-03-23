package com.ecommerce.shipping.domain.adapter;

import java.time.LocalDate;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 2 và 3: create/cancel order qua 3PL adapter.
public interface ShippingProviderAdapter {

    String providerCode();

    CreateShipmentProviderPayload createShipment(CreateShipmentProviderPayload request);

    void cancelShipment(String trackingNumber, String reason);

    TrackingProbePayload getTrackingStatus(String trackingNumber);

    record CreateShipmentProviderPayload(
            String trackingNumber,
            String labelUrl,
            LocalDate estimatedDeliveryDate,
            String providerStatus,
            String serviceType,
            Map<String, Object> requestPayload
    ) {
    }

        record TrackingProbePayload(
            String providerStatus,
            String location,
            boolean found
        ) {
        }
}

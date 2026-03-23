package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.application.dto.CreateShipmentCommand;

import java.time.LocalDate;

// Theo thiết kế shipping_workflow.md mục 2-3: gọi API 3PL create/cancel shipment.
public interface ShippingProviderGatewayPort {

    CreateShipmentProviderResponse createShipment(CreateShipmentCommand command);

    void cancelShipment(String providerCode, String trackingNumber, String reason);

    ProviderTrackingProbe getTrackingStatus(String providerCode, String trackingNumber);

    record CreateShipmentProviderResponse(
            String trackingNumber,
            String labelUrl,
            LocalDate estimatedDeliveryDate,
            String providerStatus,
            String serviceType
    ) {
    }

        record ProviderTrackingProbe(
            String providerStatus,
            String location,
            boolean found
        ) {
        }
}

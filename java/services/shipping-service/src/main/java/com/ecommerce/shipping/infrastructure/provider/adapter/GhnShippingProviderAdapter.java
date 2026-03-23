package com.ecommerce.shipping.infrastructure.provider.adapter;

import com.ecommerce.shipping.domain.adapter.ShippingProviderAdapter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_workflow.md mục 2: GHN create/cancel shipment integration.
@Component
public class GhnShippingProviderAdapter implements ShippingProviderAdapter {

    @Override
    public String providerCode() {
        return "GHN";
    }

    @Override
    @CircuitBreaker(name = "ghn")
    public CreateShipmentProviderPayload createShipment(CreateShipmentProviderPayload request) {
        String trackingNumber = request.trackingNumber() == null || request.trackingNumber().isBlank()
                ? "GHN" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
                : request.trackingNumber();
        return new CreateShipmentProviderPayload(
                trackingNumber,
                "https://label.ghn.example/" + trackingNumber,
                LocalDate.now().plusDays(2),
                "ready_to_pick",
                request.serviceType(),
                request.requestPayload());
    }

    @Override
    @CircuitBreaker(name = "ghn")
    public void cancelShipment(String trackingNumber, String reason) {
        Map.of("tracking_number", trackingNumber, "reason", reason);
    }

    @Override
    @CircuitBreaker(name = "ghn")
    public TrackingProbePayload getTrackingStatus(String trackingNumber) {
        return new TrackingProbePayload("delivering", "GHN Hub HCM", true);
    }
}

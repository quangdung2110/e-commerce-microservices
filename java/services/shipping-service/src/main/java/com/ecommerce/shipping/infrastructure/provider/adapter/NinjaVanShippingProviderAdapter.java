package com.ecommerce.shipping.infrastructure.provider.adapter;

import com.ecommerce.shipping.domain.adapter.ShippingProviderAdapter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_workflow.md mục 2: NinjaVan provider integration.
@Component
public class NinjaVanShippingProviderAdapter implements ShippingProviderAdapter {

    @Override
    public String providerCode() {
        return "NINJAVAN";
    }

    @Override
    @CircuitBreaker(name = "ninjavan")
    public CreateShipmentProviderPayload createShipment(CreateShipmentProviderPayload request) {
        String trackingNumber = request.trackingNumber() == null || request.trackingNumber().isBlank()
                ? "NV" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
                : request.trackingNumber();
        return new CreateShipmentProviderPayload(
                trackingNumber,
                "https://label.ninjavan.example/" + trackingNumber,
                LocalDate.now().plusDays(3),
                "Pending Pickup",
                request.serviceType(),
                request.requestPayload());
    }

    @Override
    @CircuitBreaker(name = "ninjavan")
    public void cancelShipment(String trackingNumber, String reason) {
        Map.of("tracking_number", trackingNumber, "reason", reason);
    }

    @Override
    @CircuitBreaker(name = "ninjavan")
    public TrackingProbePayload getTrackingStatus(String trackingNumber) {
        return new TrackingProbePayload("En-route to Sorting Hub", "NinjaVan Hub", true);
    }
}

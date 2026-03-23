package com.ecommerce.shipping.infrastructure.provider.adapter;

import com.ecommerce.shipping.domain.adapter.ShippingProviderAdapter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_state_machine.md mục 2.3: J&T provider support.
@Component
public class JtShippingProviderAdapter implements ShippingProviderAdapter {

    @Override
    public String providerCode() {
        return "JT";
    }

    @Override
    @CircuitBreaker(name = "jt")
    public CreateShipmentProviderPayload createShipment(CreateShipmentProviderPayload request) {
        String trackingNumber = request.trackingNumber() == null || request.trackingNumber().isBlank()
                ? "JT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
                : request.trackingNumber();
        return new CreateShipmentProviderPayload(
                trackingNumber,
                "https://label.jt.example/" + trackingNumber,
                LocalDate.now().plusDays(3),
                "Picked Up",
                request.serviceType(),
                request.requestPayload());
    }

    @Override
    @CircuitBreaker(name = "jt")
    public void cancelShipment(String trackingNumber, String reason) {
        Map.of("tracking_number", trackingNumber, "reason", reason);
    }

    @Override
    @CircuitBreaker(name = "jt")
    public TrackingProbePayload getTrackingStatus(String trackingNumber) {
        return new TrackingProbePayload("In Transit", "JT Transit Center", true);
    }
}

package com.ecommerce.shipping.infrastructure.provider.gateway;

import com.ecommerce.shipping.application.dto.CreateShipmentCommand;
import com.ecommerce.shipping.application.port.out.ShippingProviderGatewayPort;
import com.ecommerce.shipping.domain.adapter.ShippingProviderAdapter;
import com.ecommerce.shipping.infrastructure.provider.factory.ShippingProviderAdapterFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 2-3: gateway gọi provider adapter theo factory pattern.
@Component
public class ShippingProviderGatewayAdapter implements ShippingProviderGatewayPort {

    private final ShippingProviderAdapterFactory shippingProviderAdapterFactory;

    public ShippingProviderGatewayAdapter(ShippingProviderAdapterFactory shippingProviderAdapterFactory) {
        this.shippingProviderAdapterFactory = shippingProviderAdapterFactory;
    }

    @Override
    public CreateShipmentProviderResponse createShipment(CreateShipmentCommand command) {
        ShippingProviderAdapter adapter = shippingProviderAdapterFactory.getAdapter(command.courierCode());
        Map<String, Object> payload = new HashMap<>();
        payload.put("order_id", command.orderId());
        payload.put("parcel_id", command.parcelId());
        payload.put("service_type", command.serviceType());
        payload.put("sender", command.sender());
        payload.put("receiver", command.receiver());
        payload.put("items", command.items());
        payload.put("cod_amount", command.codAmount());
        payload.put("note", command.note());

        ShippingProviderAdapter.CreateShipmentProviderPayload providerPayload =
                adapter.createShipment(new ShippingProviderAdapter.CreateShipmentProviderPayload(
                        null,
                        null,
                        null,
                        null,
                        command.serviceType(),
                        payload));

        return new CreateShipmentProviderResponse(
                providerPayload.trackingNumber(),
                providerPayload.labelUrl(),
                providerPayload.estimatedDeliveryDate(),
                providerPayload.providerStatus(),
                providerPayload.serviceType());
    }

    @Override
    public void cancelShipment(String providerCode, String trackingNumber, String reason) {
        ShippingProviderAdapter adapter = shippingProviderAdapterFactory.getAdapter(providerCode);
        adapter.cancelShipment(trackingNumber, reason);
    }

    @Override
    public ProviderTrackingProbe getTrackingStatus(String providerCode, String trackingNumber) {
        ShippingProviderAdapter adapter = shippingProviderAdapterFactory.getAdapter(providerCode);
        ShippingProviderAdapter.TrackingProbePayload probe = adapter.getTrackingStatus(trackingNumber);
        return new ProviderTrackingProbe(probe.providerStatus(), probe.location(), probe.found());
    }
}

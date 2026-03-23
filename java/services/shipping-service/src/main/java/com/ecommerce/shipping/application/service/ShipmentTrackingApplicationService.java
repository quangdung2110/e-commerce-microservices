package com.ecommerce.shipping.application.service;

import com.ecommerce.shipping.application.dto.TrackingEventsResult;
import com.ecommerce.shipping.application.port.in.GetTrackingEventsUseCase;
import com.ecommerce.shipping.application.port.out.ShipmentEventStorePort;
import com.ecommerce.shipping.application.port.out.ShipmentRepositoryPort;
import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.domain.model.Shipment;
import com.ecommerce.shipping.domain.model.ShipmentEvent;
import org.springframework.stereotype.Service;

import java.util.List;

// Theo thiết kế shipping_workflow.md mục 4: tracking timeline query từ ScyllaDB.
@Service
public class ShipmentTrackingApplicationService implements GetTrackingEventsUseCase {

    private static final int DEFAULT_LIMIT = 100;

    private final ShipmentRepositoryPort shipmentRepositoryPort;
    private final ShipmentEventStorePort shipmentEventStorePort;
    private final ShippingProviderCatalogPort shippingProviderCatalogPort;

    public ShipmentTrackingApplicationService(ShipmentRepositoryPort shipmentRepositoryPort,
					      ShipmentEventStorePort shipmentEventStorePort,
					      ShippingProviderCatalogPort shippingProviderCatalogPort) {
	this.shipmentRepositoryPort = shipmentRepositoryPort;
	this.shipmentEventStorePort = shipmentEventStorePort;
	this.shippingProviderCatalogPort = shippingProviderCatalogPort;
    }

    @Override
    public TrackingEventsResult getTrackingEvents(String trackingNumber, int limit) {
	if (trackingNumber == null || trackingNumber.isBlank()) {
	    throw new IllegalArgumentException("trackingNumber must not be blank");
	}

	Shipment shipment = shipmentRepositoryPort.findByTrackingNumber(trackingNumber)
		.orElseThrow(() -> new IllegalArgumentException("Shipment not found by tracking number: " + trackingNumber));

	int readLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
	List<ShipmentEvent> timeline = shipmentEventStorePort.findLatestByShipmentId(shipment.id(), readLimit);
	String courierCode = shippingProviderCatalogPort.findById(shipment.providerId())
		.map(provider -> provider.code())
		.orElse(null);

	List<TrackingEventsResult.TrackingEventItem> items = timeline.stream()
		.map(event -> new TrackingEventsResult.TrackingEventItem(
			event.standardizedStatus().name(),
			event.location(),
			event.occurredAt()))
		.toList();

	return new TrackingEventsResult(
		shipment.trackingNumber(),
		courierCode,
		shipment.standardizedStatus().name(),
		items);
    }
}

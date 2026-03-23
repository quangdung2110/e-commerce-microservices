package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.ShipmentEvent;

import java.util.List;

// Theo thiết kế shipping_db_design.md mục 2.2: ScyllaDB shipment_events.
public interface ShipmentEventStorePort {

	void append(ShipmentEvent shipmentEvent);

	List<ShipmentEvent> findLatestByShipmentId(Long shipmentId, int limit);
}

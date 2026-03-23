package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.Shipment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 2.1: persistence shipments.
public interface ShipmentRepositoryPort {

	Shipment save(Shipment shipment);

	Optional<Shipment> findById(Long shipmentId);

	Optional<Shipment> findByTrackingNumber(String trackingNumber);

	Optional<Shipment> findByParcelId(Long parcelId);

	boolean updateStatusWithOptimisticLock(Long shipmentId,
										   long expectedVersion,
										   String providerStatus,
										   String standardizedStatus,
										   OffsetDateTime updatedAt);

	List<Shipment> findStaleShipments(OffsetDateTime before, int limit);
}

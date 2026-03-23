package com.ecommerce.shipping.infrastructure.persistence.adapter;

import com.ecommerce.shipping.application.port.out.ShipmentEventStorePort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.model.ShipmentEvent;
import com.ecommerce.shipping.infrastructure.persistence.scylla.entity.ShipmentEventPrimaryKey;
import com.ecommerce.shipping.infrastructure.persistence.scylla.entity.ShipmentEventRow;
import com.ecommerce.shipping.infrastructure.persistence.scylla.repository.ShipmentEventScyllaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

// Theo thiết kế shipping_db_design.md mục 2.2 và shipping_workflow.md mục 4: append-only event history trên Scylla.
@Component
public class ShipmentEventStoreScyllaAdapter implements ShipmentEventStorePort {

    private final ShipmentEventScyllaRepository shipmentEventScyllaRepository;

    public ShipmentEventStoreScyllaAdapter(ShipmentEventScyllaRepository shipmentEventScyllaRepository) {
        this.shipmentEventScyllaRepository = shipmentEventScyllaRepository;
    }

    @Override
    public void append(ShipmentEvent shipmentEvent) {
        ShipmentEventRow row = new ShipmentEventRow();
        row.setPrimaryKey(new ShipmentEventPrimaryKey(shipmentEvent.shipmentId(), shipmentEvent.occurredAt()));
        row.setStandardizedStatus(shipmentEvent.standardizedStatus().name());
        row.setProviderStatus(shipmentEvent.providerStatus());
        row.setDescription(shipmentEvent.description());
        row.setLocation(shipmentEvent.location());
        row.setRawPayload(shipmentEvent.rawPayload());
        shipmentEventScyllaRepository.save(row);
    }

    @Override
    public List<ShipmentEvent> findLatestByShipmentId(Long shipmentId, int limit) {
        return shipmentEventScyllaRepository.findLatestByShipmentId(shipmentId, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private ShipmentEvent toDomain(ShipmentEventRow row) {
        return new ShipmentEvent(
                row.getPrimaryKey().getShipmentId(),
                row.getPrimaryKey().getOccurredAt(),
                ShipmentStatus.valueOf(row.getStandardizedStatus()),
                row.getProviderStatus(),
                row.getDescription(),
                row.getLocation(),
                row.getRawPayload());
    }
}

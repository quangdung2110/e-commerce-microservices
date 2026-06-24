package com.platform.inventoryservice.service;

import com.platform.inventoryservice.domain.event.ShipToCustomerPayload;
import com.platform.inventoryservice.domain.exception.ReservationNotFoundException;
import com.platform.inventoryservice.domain.exception.WarehouseNotFoundException;
import com.platform.inventoryservice.infrastructure.event.CloudEventImpl;
import com.platform.inventoryservice.infrastructure.event.InventoryEventBuilder;
import com.platform.inventoryservice.infrastructure.outbox.TransactionalOutboxWriter;
import com.platform.inventoryservice.port.inbound.ShipToCustomerPort;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.database.WarehouseRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.service.command.ShipToCustomerCommand;
import com.platform.inventoryservice.service.result.ShipToCustomerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipToCustomerService implements ShipToCustomerPort {

    private final WarehouseRepository warehouseRepo;
    private final InventoryReservationRepository reservationRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final InventoryEventBuilder eventBuilder;
    private final InventoryMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ShipToCustomerResult ship(ShipToCustomerCommand cmd) {
        // 1. Validate warehouse
        warehouseRepo.findById(cmd.warehouseId())
            .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

        // 2. Validate & count DEDUCTED reservations at this warehouse
        List<Long> itemIds = reservationRepo.findDeductedItemIds(
            cmd.orderId(), cmd.warehouseId());
        if (itemIds.isEmpty()) {
            throw new ReservationNotFoundException(
                "No DEDUCTED reservation for order=" + cmd.orderId()
                + " at warehouse=" + cmd.warehouseId());
        }

        // 3. Build outbox event (signal only — no stock mutation)
        CloudEventImpl<ShipToCustomerPayload> envelope = eventBuilder.buildShipToCustomerEvent(
            new ShipToCustomerPayload(cmd.orderId(), cmd.warehouseId(), cmd.shippedAt(), cmd.courierNote()),
            cmd.correlationId()
        );
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
        outboxWriter.writeCloudEvent(envelope, payloadJson);

        metrics.recordShip(itemIds.size());

        return new ShipToCustomerResult(
            true, cmd.orderId(), cmd.warehouseId(),
            cmd.shippedAt(), envelope.eventId(), itemIds.size()
        );
    }
}

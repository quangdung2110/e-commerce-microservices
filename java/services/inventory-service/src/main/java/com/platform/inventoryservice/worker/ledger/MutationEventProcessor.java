package com.platform.inventoryservice.worker.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.domain.entity.InventoryLedgerEntry;
import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.entity.PhysicalInventory;
import com.platform.inventoryservice.domain.enums.ReferenceType;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.event.StockChangedPayload;
import com.platform.inventoryservice.domain.event.StockMutationPayload;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.event.CloudEventImpl;
import com.platform.inventoryservice.infrastructure.event.EventDeserializer;
import com.platform.inventoryservice.infrastructure.event.InventoryEventBuilder;
import com.platform.inventoryservice.infrastructure.outbox.TransactionalOutboxWriter;
import com.platform.inventoryservice.port.outbound.database.InventoryLedgerRepository;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.database.PhysicalInventoryRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MutationEventProcessor {

    private final InventoryLedgerRepository ledgerRepo;
    private final InventoryReservationRepository reservationRepo;
    private final PhysicalInventoryRepository physicalRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final EventDeserializer deserializer;
    private final InventoryEventBuilder eventBuilder;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    @Transactional
    public void processBatch(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            try {
                processRecord(record.value());
            } catch (Exception e) {
                log.error("Failed to process ledger event: {}", record.value(), e);
                throw e; // rollback transaction
            }
        }
    }

    private void processRecord(String json) {
        CloudEventImpl<JsonNode> envelope = deserializer.deserialize(json);
        if (!"INVENTORY_STOCK_MUTATION".equals(envelope.eventType())) {
            return;
        }

        UUID eventId = envelope.eventId();
        if (ledgerRepo.existsByEventId(eventId)) {
            log.warn("Duplicate event detected in ledger, skipping. event_id={}", eventId);
            return;
        }

        StockMutationPayload payload = deserializer.extractPayload(envelope, StockMutationPayload.class);
        processMutation(eventId, payload);
    }

    private void processMutation(UUID eventId, StockMutationPayload p) {
        SkuWarehouseKey key = new SkuWarehouseKey(p.skuId(), p.warehouseId());
        ReferenceType refType = mapToRefType(p.changeType());
        int ledgerQty = 0;
        int balanceAfter;

        switch (p.changeType()) {
            case "ORDER_RESERVED":
                Instant expiresAt = Instant.now().plus(props.getRestock().getReservationTtlMinutes(), ChronoUnit.MINUTES);
                InventoryReservation reservation = InventoryReservation.newReserved(
                    p.orderId(), p.orderItemId(), p.skuId(), p.productId(), p.warehouseId(), p.quantity(), null, expiresAt
                );
                reservationRepo.insertIfAbsent(reservation);
                
                physicalRepo.adjustReserved(key, p.quantity());
                balanceAfter = getActualQuantity(key);
                break;

            case "ORDER_DEDUCTED":
                updateReservationStatus(p, ReservationStatus.DEDUCTED);
                balanceAfter = physicalRepo.applyDeduct(key, p.quantity());
                ledgerQty = -p.quantity();
                break;

            case "ORDER_CANCELLED":
                updateReservationStatus(p, ReservationStatus.CANCELLED);
                physicalRepo.adjustReserved(key, -p.quantity());
                balanceAfter = getActualQuantity(key);
                break;

            case "RESTOCK":
            case "RESTOCK_RETURN":
                PhysicalInventory updatedAdd = physicalRepo.upsert(key, p.productId(), p.quantity());
                balanceAfter = updatedAdd.getActualQuantity();
                ledgerQty = p.quantity();
                break;

            case "ADJUST_ADD":
                PhysicalInventory adjustedAdd = physicalRepo.upsert(key, p.productId(), p.quantity());
                balanceAfter = adjustedAdd.getActualQuantity();
                ledgerQty = p.quantity();
                break;

            case "ADJUST_SUB":
                PhysicalInventory adjustedSub = physicalRepo.upsert(key, p.productId(), -p.quantity());
                balanceAfter = adjustedSub.getActualQuantity();
                ledgerQty = -p.quantity();
                break;

            default:
                log.warn("Unknown mutation changeType: {}", p.changeType());
                return;
        }

        // 1. Insert Ledger Entry
        InventoryLedgerEntry ledgerEntry = InventoryLedgerEntry.append(
            eventId, p.skuId(), p.productId(), p.warehouseId(), refType, ledgerQty, balanceAfter, p.correlationId()
        );
        ledgerRepo.append(ledgerEntry);

        // 2. Emit Outbox Event (INVENTORY_STOCK_CHANGED)
        emitStockChangedOutbox(p, refType);
    }

    private int getActualQuantity(SkuWarehouseKey key) {
        return physicalRepo.findByKey(key)
                .map(PhysicalInventory::getActualQuantity)
                .orElse(0);
    }

    private void updateReservationStatus(StockMutationPayload p, ReservationStatus status) {
        Optional<InventoryReservation> resOpt = reservationRepo.findActiveByOrderSkuWarehouse(
            p.orderId(), p.skuId(), p.warehouseId()
        );
        resOpt.ifPresent(res -> reservationRepo.updateStatus(res.getId(), status));
    }

    private ReferenceType mapToRefType(String changeType) {
        return switch (changeType) {
            case "ORDER_RESERVED" -> ReferenceType.ORDER_RESERVED;
            case "ORDER_DEDUCTED" -> ReferenceType.ORDER_DEDUCTED;
            case "ORDER_CANCELLED" -> ReferenceType.ORDER_CANCELLED;
            case "RESTOCK" -> ReferenceType.ADMIN_RESTOCK;
            case "RESTOCK_RETURN" -> ReferenceType.ORDER_RETURNED_RESTOCK;
            case "ADJUST_ADD" -> ReferenceType.ADMIN_RESTOCK;
            case "ADJUST_SUB" -> ReferenceType.ADMIN_RESTOCK;
            default -> ReferenceType.ADMIN_RESTOCK; // fallback
        };
    }

    private void emitStockChangedOutbox(StockMutationPayload p, ReferenceType refType) {
        long availableGlobal = physicalRepo.sumAvailableBySkuIds(List.of(p.skuId()))
                .getOrDefault(p.skuId(), 0L);

        StockChangedPayload changedPayload = new StockChangedPayload(
            p.productId(),
            p.skuId(),
            availableGlobal,
            refType.name()
        );

        CloudEventImpl<StockChangedPayload> outboxEvent = eventBuilder.buildStockChangedEvent(changedPayload, p.correlationId());
        outboxWriter.writeCloudEvent(outboxEvent, outboxEvent.toJsonString());
        metrics.recordMutationProcessed(p.changeType());
    }
}

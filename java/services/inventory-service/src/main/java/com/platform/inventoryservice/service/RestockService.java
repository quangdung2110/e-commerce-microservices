package com.platform.inventoryservice.service;

import com.platform.inventoryservice.domain.entity.InventoryLedgerEntry;
import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.entity.PhysicalInventory;
import com.platform.inventoryservice.domain.entity.Warehouse;
import com.platform.inventoryservice.domain.enums.ReferenceType;
import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.domain.event.ReturnReceivedAllPayload;
import com.platform.inventoryservice.domain.event.StockChangedPayload;
import com.platform.inventoryservice.domain.exception.RefundNotExpectedException;
import com.platform.inventoryservice.domain.exception.ReservationNotFoundException;
import com.platform.inventoryservice.domain.exception.WarehouseNotFoundException;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.event.CloudEventImpl;
import com.platform.inventoryservice.infrastructure.event.InventoryEventBuilder;
import com.platform.inventoryservice.infrastructure.outbox.TransactionalOutboxWriter;
import com.platform.inventoryservice.infrastructure.redis.key.RedisKeyBuilder;
import com.platform.inventoryservice.port.inbound.RestockPort;
import com.platform.inventoryservice.port.outbound.database.InventoryLedgerRepository;
import com.platform.inventoryservice.port.outbound.database.InventoryReservationRepository;
import com.platform.inventoryservice.port.outbound.database.PhysicalInventoryRepository;
import com.platform.inventoryservice.port.outbound.database.WarehouseRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.service.command.RestockCommand;
import com.platform.inventoryservice.service.result.RefundSnapshot;
import com.platform.inventoryservice.service.result.RestockResult;
import com.platform.inventoryservice.service.result.StockSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RestockService implements RestockPort {

    private final WarehouseRepository warehouseRepo;
    private final PhysicalInventoryRepository physicalRepo;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryLedgerRepository ledgerRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final InventoryEventBuilder eventBuilder;
    private final StringRedisTemplate redis;
    private final RedisKeyBuilder redisKeys;
    private final InventoryMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public RestockResult restock(RestockCommand cmd) {
        // 1. Validate warehouse exists & active
        Warehouse warehouse = warehouseRepo.findById(cmd.warehouseId())
            .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

        // 2. Upsert physical_inventory (atomic increase actual_quantity)
        PhysicalInventory physicalInventory = physicalRepo.upsert(
            new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId()),
            cmd.productId(),
            cmd.quantity()
        );
        int newActual = physicalInventory.getActualQuantity();

        // 3. Reference-type specific logic
        RefundSnapshot refundSnapshot = null;
        if (cmd.referenceType() == ReferenceType.ORDER_RETURNED_RESTOCK) {
            refundSnapshot = handleReturnRestock(cmd);
        }

        // 4. Append ledger
        UUID ledgerEventId = UUID.randomUUID();
        InventoryLedgerEntry ledger = InventoryLedgerEntry.append(
            ledgerEventId,
            cmd.skuId(), cmd.productId(), cmd.warehouseId(),
            cmd.referenceType(),
            cmd.quantity(),
            newActual,
            cmd.correlationId()
        );
        ledgerRepo.append(ledger);

        // 5. Compute aggregate per-SKU available for outbox payload
        long aggregateAvailable = physicalRepo.sumAvailableBySkuIds(List.of(cmd.skuId()))
            .getOrDefault(cmd.skuId(), 0L);

        // 6. Insert outbox INVENTORY_STOCK_CHANGED (cold track)
        String stockChangedType = cmd.referenceType().name();
        CloudEventImpl<StockChangedPayload> stockChangedEvent = eventBuilder.buildStockChangedEvent(
            new StockChangedPayload(cmd.productId(), cmd.skuId(), (int) aggregateAvailable, stockChangedType),
            cmd.correlationId()
        );
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(stockChangedEvent.payload());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
        outboxWriter.writeCloudEvent(stockChangedEvent, payloadJson);

        // 7. If refund completion -> emit INVENTORY_RETURN_RECEIVED_ALL
        if (refundSnapshot != null && refundSnapshot.allItemsReceived()) {
            emitReturnReceivedAll(cmd, refundSnapshot, cmd.correlationId());
        }

        // 8. Update Redis cache (best-effort)
        updateRedisCache(cmd, newActual);

        metrics.recordRestock(cmd.referenceType().name(), cmd.quantity());

        return new RestockResult(
            cmd.warehouseId(), cmd.skuId(), cmd.productId(),
            cmd.referenceType(), cmd.quantity(),
            new StockSnapshot(newActual, 0, newActual),
            refundSnapshot,
            stockChangedEvent.eventId(),
            Instant.now()
        );
    }

    private RefundSnapshot handleReturnRestock(RestockCommand cmd) {
        // 1. Find DEDUCTED reservation
        InventoryReservation reservation = reservationRepo
            .findByOrderIdAndOrderItemIdAndStatus(
                cmd.orderId(), cmd.orderItemId(), ReservationStatus.DEDUCTED)
            .orElseThrow(() -> new ReservationNotFoundException(
                "order=" + cmd.orderId() + " item=" + cmd.orderItemId()));

        if (!reservation.isRefundExpected()) {
            throw new RefundNotExpectedException(cmd.orderId(), cmd.orderItemId());
        }

        // 2. Mark refund_returned_at
        reservationRepo.markRefundReturned(cmd.orderId(), cmd.orderItemId());

        // 3. Refund completion check
        var snapshot = reservationRepo.checkRefundCompletion(cmd.orderId(), cmd.refundRequestId());

        return new RefundSnapshot(
            cmd.orderId(), cmd.orderItemId(), cmd.refundRequestId(),
            Instant.now(), // approximation, as it was just updated
            snapshot.pendingCount() == 0
        );
    }

    private void emitReturnReceivedAll(
        RestockCommand cmd, RefundSnapshot snapshot, String correlationId
    ) {
        CloudEventImpl<ReturnReceivedAllPayload> envelope = eventBuilder.buildReturnReceivedAllEvent(
            new ReturnReceivedAllPayload(
                cmd.orderId(),
                cmd.refundRequestId()
            ),
            correlationId
        );
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
        outboxWriter.writeReturnReceivedAll(
            cmd.orderId(), cmd.refundRequestId(),
            payloadJson);
    }

    private void updateRedisCache(RestockCommand cmd, int newActual) {
        try {
            String key = redisKeys.stockKey(new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId()));
            Map<String, String> hash = Map.of(
                "total", String.valueOf(newActual),
                "reserved", "0",
                "available", String.valueOf(newActual),
                "updated_at_ms", String.valueOf(System.currentTimeMillis())
            );
            redis.opsForHash().putAll(key, hash);
        } catch (Exception e) {
            log.warn("Redis cache update failed (non-fatal) for sku={} wh={}",
                     cmd.skuId(), cmd.warehouseId(), e);
        }
    }
}

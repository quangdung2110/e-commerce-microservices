package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.OutboxEventType;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity cho bảng {@code inventory_outbox}.
 *
 * <p>Aggregate ID convention:
 * <ul>
 *   <li>{@code INVENTORY_STOCK_CHANGED} → {@code aggregateId = skuId}, {@code partitionKey = skuId}</li>
 *   <li>{@code INVENTORY_RETURN_RECEIVED_ALL} → {@code aggregateId = orderId}, {@code partitionKey = orderId}</li>
 *   <li>{@code INVENTORY_SHIP_TO_CUSTOMER} → {@code aggregateId = orderId}, {@code partitionKey = orderId}</li>
 * </ul>
 */
@Getter
public class OutboxEvent {

    private UUID eventId;
    private OutboxEventType eventType;
    private int schemaVersion;
    private String aggregateType;
    private String aggregateId;
    private String partitionKey;
    private String refundRequestId;
    private String payload;
    private Instant createdAt;

    public OutboxEvent() {
    }

    public static OutboxEvent of(
            OutboxEventType eventType,
            String aggregateId,
            String partitionKey,
            String payloadJson) {
        return of(eventType, aggregateId, partitionKey, payloadJson, null);
    }

    public static OutboxEvent of(
            OutboxEventType eventType,
            String aggregateId,
            String partitionKey,
            String payloadJson,
            String refundRequestId) {
        return of(UUID.randomUUID(), eventType, aggregateId, partitionKey, payloadJson, refundRequestId);
    }

    public static OutboxEvent of(
            UUID eventId,
            OutboxEventType eventType,
            String aggregateId,
            String partitionKey,
            String payloadJson,
            String refundRequestId) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.eventType = eventType;
        e.schemaVersion = 1;
        e.aggregateType = "Inventory";
        e.aggregateId = aggregateId;
        e.partitionKey = partitionKey;
        e.refundRequestId = refundRequestId;
        e.payload = payloadJson;
        e.createdAt = Instant.now();
        return e;
    }

    public boolean isReturnReceivedAll() {
        return eventType == OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL;
    }
}
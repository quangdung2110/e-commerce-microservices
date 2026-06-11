package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox event entity.
 * Represents a domain event to be published via the outbox pattern.
 */
public class OutboxEvent {

    private UUID eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String partitionKey;
    private String payload;
    private LocalDateTime createdAt;

    public OutboxEvent() {}

    public static OutboxEvent of(DomainEvent event, String aggregateId, String partitionKey, String payloadJson) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.eventId = UUID.randomUUID();
        outboxEvent.eventType = event.getEventType();
        outboxEvent.aggregateType = event.getAggregateType();
        outboxEvent.aggregateId = aggregateId;
        outboxEvent.partitionKey = partitionKey;
        outboxEvent.payload = payloadJson;
        outboxEvent.createdAt = LocalDateTime.now();
        return outboxEvent;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getPartitionKey() { return partitionKey; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public void setPartitionKey(String partitionKey) { this.partitionKey = partitionKey; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

package com.platform.productservice.domain.event;

import com.platform.productservice.common.constants.ServiceConstants;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public abstract class DomainEvent {

    private final UUID eventId;
    private final String eventType;
    private final String aggregateType;
    private final String aggregateId;
    private final String partitionKey;
    private final Instant timestamp;
    private final int schemaVersion;
    private final String source;

    protected DomainEvent(String eventType, String aggregateType, String aggregateId, String partitionKey) {
        this.eventId = UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.partitionKey = partitionKey;
        this.timestamp = Instant.now();
        this.schemaVersion = ServiceConstants.SCHEMA_VERSION;
        this.source = ServiceConstants.SERVICE_NAME;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getPartitionKey() { return partitionKey; }
    public Instant getTimestamp() { return timestamp; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getSource() { return source; }

    public Map<String, Object> toCloudEventPayload() {
        return Map.of(
                "id", eventId.toString(),
                "type", eventType,
                "source", source,
                "specversion", "1.0",
                "datacontenttype", "application/json",
                "time", timestamp.toString(),
                "schemaVersion", schemaVersion,
                "aggregateType", aggregateType,
                "aggregateId", aggregateId,
                "partitionKey", partitionKey
        );
    }
}

package com.platform.productservice.infrastructure.kafka.deserializer;

import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudEvent {

    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String partitionKey;
    private String correlationId;
    private String timestamp;
    private String source;
    private String schemaVersion;
    private Map<String, Object> payload;

    public static CloudEvent fromDomainEvent(
            UUID eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String partitionKey,
            String correlationId,
            Instant timestamp,
            String source,
            int schemaVersion,
            Map<String, Object> payload) {
        return CloudEvent.builder()
                .eventId(eventId.toString())
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .partitionKey(partitionKey)
                .correlationId(correlationId)
                .timestamp(timestamp.toString())
                .source(source)
                .schemaVersion(String.valueOf(schemaVersion))
                .payload(payload)
                .build();
    }
}

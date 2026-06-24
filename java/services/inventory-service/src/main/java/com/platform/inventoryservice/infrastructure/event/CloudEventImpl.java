package com.platform.inventoryservice.infrastructure.event;

import com.platform.inventoryservice.domain.event.CloudEvent;
import java.time.Instant;
import java.util.UUID;

public record CloudEventImpl<P>(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String correlationId,
        Instant timestamp,
        String source,
        int schemaVersion,
        P payload
) implements CloudEvent<P> {

    public String toJsonString() {
        return EventSerializer.toJson(this);
    }
}

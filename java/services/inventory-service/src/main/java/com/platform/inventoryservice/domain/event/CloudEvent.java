package com.platform.inventoryservice.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CloudEvents envelope chuẩn cho mọi event phát ra từ Inventory Service.
 * Implementation đầy đủ + Kafka serialization ở M07.
 */
public interface CloudEvent<P> {

    UUID eventId();

    String eventType();

    String aggregateType();

    String aggregateId();

    String correlationId();

    Instant timestamp();

    String source();

    int schemaVersion();

    P payload();
}
package com.platform.productservice.infrastructure.event;

import com.platform.productservice.common.Uuids;
import com.platform.productservice.domain.entity.OutboxEventEntity;
import com.platform.productservice.domain.enums.AggregateType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Ghi CloudEvent vào outbox trong cùng transaction với thay đổi nghiệp vụ.
 * Không có publisher Kafka trực tiếp: Debezium đọc WAL của bảng này (ADR-012).
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param eventId  trùng CloudEvents {@code id} để consumer dedupe
     * @param payload  CloudEvent structured JSON hoàn chỉnh
     */
    public void append(UUID eventId,
                       String eventType,
                       AggregateType aggregateType,
                       UUID aggregateId,
                       UUID partitionKey,
                       String payload,
                       Instant now) {
        if (partitionKey == null) {
            throw new IllegalArgumentException("partition_key must not be null for event " + eventType);
        }
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setEventId(eventId == null ? Uuids.next() : eventId);
        entity.setEventType(eventType);
        entity.setAggregateType(aggregateType);
        entity.setAggregateId(aggregateId);
        entity.setPartitionKey(partitionKey);
        entity.setPayload(payload);
        entity.setCreatedAt(now);
        entityManager.persist(entity);
    }
}

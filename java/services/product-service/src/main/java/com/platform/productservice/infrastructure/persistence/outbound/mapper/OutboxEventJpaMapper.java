package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventJpaMapper {

    public OutboxEventJpaMapper() {
    }

    public OutboxEvent toDomain(OutboxEventJpaEntity entity) {
        if (entity == null) return null;

        OutboxEvent event = new OutboxEvent();
        event.setEventId(entity.getEventId());
        event.setEventType(entity.getEventType());
        event.setAggregateType(entity.getAggregateType());
        event.setAggregateId(entity.getAggregateId());
        event.setPartitionKey(entity.getPartitionKey());
        event.setPayload(entity.getPayload());
        event.setCreatedAt(entity.getCreatedAt());
        event.setSource(entity.getSource());
        event.setProcessed(entity.getProcessed());
        return event;
    }

    public OutboxEventJpaEntity toEntity(OutboxEvent domain, String payloadJson) {
        if (domain == null) return null;

        OutboxEventJpaEntity entity = new OutboxEventJpaEntity();
        entity.setEventId(domain.getEventId());
        entity.setEventType(domain.getEventType());
        entity.setAggregateType(domain.getAggregateType());
        entity.setAggregateId(domain.getAggregateId());
        entity.setPartitionKey(domain.getPartitionKey());
        entity.setPayload(payloadJson);
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setSource(domain.getSource());
        entity.setProcessed(domain.getProcessed() != null ? domain.getProcessed() : false);
        return entity;
    }
}

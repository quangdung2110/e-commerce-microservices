package com.ecommerce.shipping.infrastructure.persistence.adapter;

import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.OutboxEventEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.OutboxEventJpaRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Theo thiết kế shipping_workers.md mục 1: transactional outbox polling và status transition.
@Component
public class OutboxPersistenceAdapter implements OutboxRepositoryPort {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final PersistenceJsonMapper persistenceJsonMapper;

    public OutboxPersistenceAdapter(OutboxEventJpaRepository outboxEventJpaRepository,
                                    PersistenceJsonMapper persistenceJsonMapper) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.persistenceJsonMapper = persistenceJsonMapper;
    }

    @Override
    public OutboxEvent save(OutboxEvent outboxEvent) {
        OutboxEventEntity saved = outboxEventJpaRepository.save(toEntity(outboxEvent));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public List<OutboxEvent> pollPending(int limit) {
        return outboxEventJpaRepository.pollPendingForPublish(limit).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void markPublished(UUID eventId, OffsetDateTime publishedAt) {
        outboxEventJpaRepository.markPublished(eventId, publishedAt);
    }

    @Override
    @Transactional
    public void markFailed(UUID eventId, int retryCount, OffsetDateTime nextRetryAt) {
        outboxEventJpaRepository.markFailed(eventId, retryCount, nextRetryAt);
    }

    @Override
    @Transactional
    public void markDead(UUID eventId, int retryCount) {
        outboxEventJpaRepository.markDead(eventId, retryCount);
    }

    private OutboxEventEntity toEntity(OutboxEvent outboxEvent) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setId(outboxEvent.id());
        entity.setAggregateType(outboxEvent.aggregateType());
        entity.setAggregateId(outboxEvent.aggregateId());
        entity.setEventType(outboxEvent.eventType());
        entity.setPayload(persistenceJsonMapper.writeMap(outboxEvent.payload()));
        entity.setStatus(outboxEvent.status());
        entity.setRetryCount(outboxEvent.retryCount());
        entity.setMaxRetries(outboxEvent.maxRetries());
        entity.setPublishedAt(outboxEvent.publishedAt());
        entity.setNextRetryAt(outboxEvent.nextRetryAt());
        entity.setCreatedAt(outboxEvent.createdAt());
        return entity;
    }

    private OutboxEvent toDomain(OutboxEventEntity entity) {
        return new OutboxEvent(
                entity.getId(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                persistenceJsonMapper.readMap(entity.getPayload()),
                entity.getStatus(),
                entity.getRetryCount(),
                entity.getMaxRetries(),
                entity.getPublishedAt(),
                entity.getNextRetryAt(),
                entity.getCreatedAt());
    }
}

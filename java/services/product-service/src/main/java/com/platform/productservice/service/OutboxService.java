package com.platform.productservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for saving domain events to the outbox table.
 * Called by business services within transactional boundaries to ensure
 * events are persisted atomically with domain changes.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Saves a single domain event to the outbox.
     *
     * @param event        the domain event
     * @param aggregateId the aggregate ID (e.g., product ID)
     * @param partitionKey the partition key for Kafka partitioning
     */
    public void saveEvent(DomainEvent event, String aggregateId, String partitionKey) {
        try {
            String payload = objectMapper.writeValueAsString(event.toCloudEventPayload());
            OutboxEvent outboxEvent = OutboxEvent.of(event, aggregateId, partitionKey, payload);
            outboxRepository.save(outboxEvent);
            log.debug("OutboxService: saved event {} for aggregate {}",
                    event.getEventType(), aggregateId);
        } catch (JsonProcessingException e) {
            log.error("OutboxService: failed to serialize event {} for aggregate {}: {}",
                    event.getEventType(), aggregateId, e.getMessage(), e);
            throw new RuntimeException("Failed to serialize domain event", e);
        }
    }

    /**
     * Batch saves multiple domain events to the outbox.
     *
     * @param events        the list of domain events
     * @param aggregateIds  the corresponding aggregate IDs
     * @param partitionKeys the corresponding partition keys
     */
    public void saveEvents(List<DomainEvent> events, List<String> aggregateIds, List<String> partitionKeys) {
        if (events == null || events.isEmpty()) {
            return;
        }

        try {
            List<OutboxEvent> outboxEvents = events.stream()
                    .map(event -> {
                        int idx = events.indexOf(event);
                        String aggregateId = aggregateIds != null && idx < aggregateIds.size()
                                ? aggregateIds.get(idx) : event.getAggregateId();
                        String partitionKey = partitionKeys != null && idx < partitionKeys.size()
                                ? partitionKeys.get(idx) : event.getPartitionKey();
                        try {
                            String payload = objectMapper.writeValueAsString(event.toCloudEventPayload());
                            return OutboxEvent.of(event, aggregateId, partitionKey, payload);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to serialize event: " + event.getEventType(), e);
                        }
                    })
                    .toList();

            outboxRepository.saveAll(outboxEvents);
            log.debug("OutboxService: saved {} events in batch", events.size());
        } catch (RuntimeException e) {
            log.error("OutboxService: failed to batch serialize events: {}", e.getMessage(), e);
            throw e;
        }
    }
}

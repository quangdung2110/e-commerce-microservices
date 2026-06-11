package com.platform.productservice.service;

import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Helper service for saving domain events to the outbox table within an existing transaction.
 * This class intentionally does NOT start a new transaction - it participates in whatever
 * transactional context the caller has already established.
 *
 * <p>This is the recommended way for business services to emit events: call this helper
 * inside a service method that is already annotated with {@code @Transactional}, ensuring
 * both the domain changes and the outbox insert are committed atomically.</p>
 *
 * <p>Note: The primary path for event publishing is via Debezium CDC reading the outbox table.
 * This helper and the direct Kafka producer are available as alternatives when needed.</p>
 */
@Service
public class TransactionalOutboxHelper {

    private static final Logger log = LoggerFactory.getLogger(TransactionalOutboxHelper.class);

    private final OutboxRepository outboxRepository;
    private final OutboxService outboxService;

    public TransactionalOutboxHelper(OutboxRepository outboxRepository, OutboxService outboxService) {
        this.outboxRepository = outboxRepository;
        this.outboxService = outboxService;
    }

    /**
     * Saves a single domain event to the outbox within the current transaction.
     * This method does NOT start a new transaction - it reuses the caller's transaction.
     *
     * @param event        the domain event to persist
     * @param aggregateId  the aggregate ID for the event
     * @param partitionKey the partition key for Kafka partitioning
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEventInTransaction(DomainEvent event, String aggregateId, String partitionKey) {
        if (event == null) {
            log.warn("TransactionalOutboxHelper: received null event, skipping");
            return;
        }
        outboxService.saveEvent(event, aggregateId, partitionKey);
        log.debug("TransactionalOutboxHelper: saved event {} for aggregate {} in existing transaction",
                event.getEventType(), aggregateId);
    }

    /**
     * Batch saves multiple domain events to the outbox within the current transaction.
     * All events are persisted in the same transaction as the domain operation.
     * This method does NOT start a new transaction - it reuses the caller's transaction.
     *
     * @param events        the list of domain events to persist
     * @param aggregateIds  the corresponding aggregate IDs
     * @param partitionKeys the corresponding partition keys
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEventsInTransaction(
            List<DomainEvent> events,
            List<String> aggregateIds,
            List<String> partitionKeys) {
        if (events == null || events.isEmpty()) {
            log.debug("TransactionalOutboxHelper: received empty event list, skipping");
            return;
        }
        outboxService.saveEvents(events, aggregateIds, partitionKeys);
        log.debug("TransactionalOutboxHelper: saved {} events in existing transaction", events.size());
    }
}

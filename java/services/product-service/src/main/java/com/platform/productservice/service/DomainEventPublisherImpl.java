package com.platform.productservice.service;

import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.port.outbound.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementation of {@link DomainEventPublisher} that delegates to {@link OutboxService}.
 * Note: actual Kafka publishing is handled by Debezium CDC (Module 09).
 */
@Service
public class DomainEventPublisherImpl implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisherImpl.class);

    private final OutboxService outboxService;

    public DomainEventPublisherImpl(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Override
    public void publish(DomainEvent event) {
        if (event == null) {
            log.warn("DomainEventPublisher: received null event, skipping");
            return;
        }
        outboxService.saveEvent(event, event.getAggregateId(), event.getPartitionKey());
        log.debug("DomainEventPublisher: published event {} for aggregate {}",
                event.getEventType(), event.getAggregateId());
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            log.debug("DomainEventPublisher: received empty event list, skipping");
            return;
        }

        List<String> aggregateIds = events.stream()
                .map(DomainEvent::getAggregateId)
                .toList();

        List<String> partitionKeys = events.stream()
                .map(DomainEvent::getPartitionKey)
                .toList();

        outboxService.saveEvents(events, aggregateIds, partitionKeys);
        log.debug("DomainEventPublisher: published {} events", events.size());
    }
}

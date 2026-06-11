package com.platform.productservice.port.outbound;

import com.platform.productservice.domain.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for publishing domain events via the outbox pattern.
 * Implementations save events to the outbox table within the current transaction.
 */
public interface DomainEventPublisher {

    /**
     * Publishes a single domain event by saving it to the outbox.
     * The event is persisted within the current transaction and will be
     * published to Kafka by the Debezium CDC connector (Module 09).
     *
     * @param event the domain event to publish
     */
    void publish(DomainEvent event);

    /**
     * Publishes multiple domain events by batch-saving them to the outbox.
     * All events are persisted within the current transaction.
     *
     * @param events the list of domain events to publish
     */
    void publishAll(List<DomainEvent> events);
}

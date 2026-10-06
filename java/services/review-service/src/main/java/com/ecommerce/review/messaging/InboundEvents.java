package com.ecommerce.review.messaging;

import com.ecommerce.review.common.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Parses an inbound structured-mode CloudEvent and validates the attributes Review
 * depends on.
 *
 * <p>A message whose envelope is unusable is a contract failure, not a transient one:
 * the {@code id} is what makes every consumer idempotent, so an event without a UUID id
 * cannot be processed safely and belongs in the dead-letter topic.
 */
@Component
public class InboundEvents {

    private final ObjectMapper eventMapper;

    public InboundEvents(@Qualifier("eventObjectMapper") ObjectMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public CloudEventEnvelope parse(String body) {
        try {
            return eventMapper.readValue(body, CloudEventEnvelope.class);
        } catch (Exception e) {
            throw new ContractViolationException("the message body is not a CloudEvents envelope", e);
        }
    }

    /**
     * The deduplication key of every consumer.
     *
     * <p>Auth asserts a UUID {@code id} in its common envelope and Review stores that
     * value unchanged in both the receipt and {@code source_event_id}, so a non-UUID id
     * has to fail here rather than be coerced into something storable.
     */
    public UUID requireEventId(CloudEventEnvelope envelope) {
        UUID eventId = Uuids.parseOrNull(envelope.id());
        if (eventId == null) {
            throw new ContractViolationException("CloudEvents id is not a UUID: " + envelope.id(), null);
        }
        return eventId;
    }

    public <T> T bind(CloudEventEnvelope envelope, Class<T> type) {
        if (envelope.data() == null) {
            throw new ContractViolationException("the CloudEvent carries no data", null);
        }
        try {
            return eventMapper.treeToValue(envelope.data(), type);
        } catch (Exception e) {
            throw new ContractViolationException(
                    "the CloudEvent data does not bind to " + type.getSimpleName(), e);
        }
    }

    /** A message that violates its contract; retrying it cannot help. */
    public static class ContractViolationException extends RuntimeException {
        public ContractViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

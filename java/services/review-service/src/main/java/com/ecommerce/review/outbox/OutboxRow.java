package com.ecommerce.review.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code review_outbox} row, which is the wire message.
 *
 * <p>{@code payload} is a complete CloudEvents 1.0 envelope and reaches Kafka verbatim;
 * the Debezium {@code EventRouter} only selects topic, key and headers. The other
 * columns are a projection of that envelope, held in step by
 * {@code ck_outbox_columns_mirror_payload} (ADR-003).
 *
 * @param createdAt the transaction timestamp, equal to {@code payload.time}
 */
public record OutboxRow(
        UUID eventId,
        ReviewEventType eventType,
        String aggregateId,
        String partitionKey,
        String correlationId,
        String payload,
        Instant createdAt) {
}

package com.ecommerce.review.outbox;

import com.ecommerce.review.common.RequestContext;
import com.ecommerce.review.common.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the complete CloudEvents envelope of every outbound Review message.
 *
 * <p>Debezium never assembles an envelope, so a missing {@code subject} or
 * {@code dataschema} is not a downstream bug: it fails the domain transaction against
 * {@code ck_outbox_payload_is_cloudevent} (ADR-003). Every attribute is written here,
 * inside the same transaction as the domain change.
 */
@Component
public class ReviewEventFactory {

    /** {@code source} is constant for every message Review publishes. */
    public static final String SOURCE = "/services/review";

    private static final String SPEC_VERSION = "1.0";
    private static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper eventMapper;

    public ReviewEventFactory(@Qualifier("eventObjectMapper") ObjectMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /** {@code REVIEW_CREATED}, {@code REVIEW_HIDDEN} or {@code REVIEW_APPROVED}. */
    public OutboxRow lifecycle(ReviewEventType type, ReviewEventData.Lifecycle data, Instant now) {
        return build(type, reviewSubject(data.reviewId()), data.reviewId().toString(),
                data.productId(), null, data, now);
    }

    public OutboxRow updated(ReviewEventData.Updated data, Instant now) {
        return build(ReviewEventType.REVIEW_UPDATED, reviewSubject(data.reviewId()),
                data.reviewId().toString(), data.productId(), null, data, now);
    }

    public OutboxRow removed(ReviewEventData.Removed data, Instant now) {
        return build(ReviewEventType.REVIEW_REMOVED, reviewSubject(data.reviewId()),
                data.reviewId().toString(), data.productId(), null, data, now);
    }

    /**
     * The rating snapshot. It is the only message with a {@code sequence}, and the only
     * one whose subject is the aggregate rather than a review; {@code sequence} always
     * equals {@code data.review_revision}, which is the guard Search applies on.
     */
    public OutboxRow metrics(ReviewEventData.Metrics data, Instant now) {
        return build(ReviewEventType.REVIEW_METRICS_UPDATED,
                "product-rating-aggregates/" + data.productId(),
                data.productId(), data.productId(), data.reviewRevision(), data, now);
    }

    /**
     * A first-reply or legal-removal notification request. Callers must have already
     * established, under the subject lock, that the eligibility owner has no deletion
     * guard: this factory writes the recipient it is given (WF-REVIEW-006, WF-REVIEW-012).
     */
    public OutboxRow notification(ReviewEventType type, ReviewEventData.Notification data, Instant now) {
        return build(type, reviewSubject(data.reviewId()), data.reviewId().toString(),
                data.productId(), null, data, now);
    }

    private static String reviewSubject(UUID reviewId) {
        return "reviews/" + reviewId;
    }

    private OutboxRow build(ReviewEventType type,
                            String subject,
                            String aggregateId,
                            String partitionKey,
                            Long sequence,
                            Object data,
                            Instant now) {
        UUID eventId = UUID.randomUUID();
        String correlationId = RequestContext.correlationId();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", SPEC_VERSION);
        // Lowercase because ck_outbox_columns_mirror_payload compares it to event_id::text.
        envelope.put("id", Uuids.canonical(eventId));
        envelope.put("type", type.type());
        envelope.put("source", SOURCE);
        envelope.put("subject", subject);
        // The transaction timestamp, the same value review_outbox.created_at receives.
        envelope.put("time", now.toString());
        envelope.put("datacontenttype", CONTENT_TYPE);
        envelope.put("dataschema", type.dataschema());
        if (sequence != null) {
            envelope.put("sequence", sequence);
        }
        if (correlationId != null) {
            envelope.put("correlationid", correlationId);
        }
        envelope.put("data", eventMapper.convertValue(data, Map.class));

        return new OutboxRow(eventId, type, aggregateId, partitionKey, correlationId,
                serialize(envelope), now);
    }

    private String serialize(Map<String, Object> envelope) {
        try {
            return eventMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Review event envelope is not serialisable", e);
        }
    }
}

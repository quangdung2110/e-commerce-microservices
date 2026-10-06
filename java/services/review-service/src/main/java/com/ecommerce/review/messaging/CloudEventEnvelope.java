package com.ecommerce.review.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A CloudEvents 1.0 structured-mode envelope read from a Kafka message body.
 *
 * <p>Structured mode is normative on every topic Review consumes: the envelope comes
 * from the body. The {@code ce_*} Kafka headers are a convenience mirror for cheap
 * filtering and must never be read in its place
 * ({@code contracts/events/review/v1/README.md}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudEventEnvelope(
        String specversion,
        String id,
        String type,
        String source,
        String subject,
        Instant time,
        String datacontenttype,
        String dataschema,
        Long sequence,
        String correlationid,
        JsonNode data) {
}

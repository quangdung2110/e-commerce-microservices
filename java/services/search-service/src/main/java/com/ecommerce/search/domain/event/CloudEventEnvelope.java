package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * CloudEvents 1.0 JSON envelope shared by all five topics Search consumes,
 * per {@code contracts/shared/json-schema/v1/cloud-event.schema.json}.
 *
 * <p>{@code data} stays a {@link JsonNode} so the consumer can dispatch on {@code type}
 * first and only then bind the payload. Unknown attributes are ignored at deserialisation
 * — adding an optional field upstream is a compatible change
 * ({@code docs/search/interfaces.md §6}).
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
        String correlationid,
        String traceparent,
        Long sequence,
        JsonNode data) {

    /** True when the envelope carries the attributes every handler needs. */
    public boolean hasRequiredAttributes() {
        return id != null && !id.isBlank()
                && type != null && !type.isBlank()
                && time != null
                && data != null && !data.isNull();
    }
}

package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * CloudEvents 1.0 wrapper for all Kafka messages consumed by Search Service.
 *
 * <p>Matches the canonical envelope defined in the Service Contract Matrix
 * (docs/_contracts/CONTRACT_MATRIX.md §0.2). Every message on Kafka topics
 * (t.product.events, t.category.events, t.review.events) is serialized in
 * this format.
 *
 * @param eventId         UUID — delivery-level unique key
 * @param eventType       e.g. PRODUCT_CREATED, SKU_UPDATED, REVIEW_METRICS_UPDATED
 * @param aggregateType   e.g. Product, ProductSku, Category
 * @param aggregateId     ID of the entity (product_id, sku_id, category_id)
 * @param correlationId   Temporal workflow ID or correlation context
 * @param businessId      Business-level ID (e.g. order_id, product_id)
 * @param timestamp       ISO 8601 UTC — event creation time
 * @param source          Source service name (e.g. product-service)
 * @param schemaVersion   Schema version (currently 1)
 * @param payload         Event-specific JSON payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudEventEnvelope(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("aggregate_type") String aggregateType,
    @JsonProperty("aggregate_id") String aggregateId,
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("business_id") String businessId,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("source") String source,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("payload") JsonNode payload
) {}

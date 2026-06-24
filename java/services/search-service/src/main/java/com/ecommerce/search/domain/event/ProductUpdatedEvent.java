package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload for {@code PRODUCT_UPDATED} events consumed from {@code t.product.events}.
 *
 * <p>Carries product-level field changes. Only the fields present in the payload
 * (and the {@code sku_ids} array) are updated — SKU-level fields are never
 * affected by this event type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductUpdatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    String status,
    String name,
    String description,
    @JsonProperty("attributes_json") Map<String, Object> attributesJson,
    List<Map<String, Object>> media,
    @JsonProperty("sku_ids") List<String> skuIds,
    @JsonProperty("updated_at") Instant updatedAt
) {}

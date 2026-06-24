package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload for {@code PRODUCT_CREATED} events consumed from {@code t.product.events}.
 *
 * <p>Carries the full product definition including its initial SKU set.
 * The worker transforms this into one {@link com.ecommerce.search.domain.document.ProductDocument}
 * per SKU in the {@code skus} array.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCreatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    String name,
    String description,
    String status,
    @JsonProperty("attributes_json") Map<String, Object> attributesJson,
    List<Map<String, Object>> media,
    List<Map<String, Object>> skus,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {}

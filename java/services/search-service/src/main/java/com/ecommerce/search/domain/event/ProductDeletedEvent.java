package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Payload for {@code PRODUCT_DELETED} events consumed from {@code t.product.events}.
 *
 * <p>Instructs the worker to tombstone all SKU documents for the product:
 * set {@code is_deleted = true}, {@code is_in_stock = false}, and record
 * the deletion timestamp.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDeletedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("sku_ids") List<String> skuIds,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("deleted_at") Instant deletedAt
) {}

package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Payload for {@code SKU_DELETED} events consumed from {@code t.product.events}.
 *
 * <p>Instructs the worker to tombstone a single SKU document:
 * set {@code is_deleted = true}, {@code is_in_stock = false}, and record
 * the deletion timestamp. Unlike {@link ProductDeletedEvent}, this event
 * targets exactly one SKU.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuDeletedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("deleted_at") Instant deletedAt
) {}

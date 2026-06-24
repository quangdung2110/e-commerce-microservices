package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Payload for {@code CATEGORY_DELETED} events consumed from {@code t.category.events}.
 *
 * <p>Instructs the worker to tombstone the category document and nullify
 * category fields on all SKU documents in the affected category subtrees.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryDeletedEvent(
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("descendant_category_ids") List<String> descendantCategoryIds,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("deleted_at") Instant deletedAt
) {}

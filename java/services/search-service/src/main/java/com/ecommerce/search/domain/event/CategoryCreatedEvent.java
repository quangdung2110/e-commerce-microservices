package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for {@code CATEGORY_CREATED} events consumed from {@code t.category.events}.
 *
 * <p>Carries the full category node definition. The worker indexes a new
 * {@link com.ecommerce.search.domain.document.CategoryDocument} via
 * scripted upsert.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryCreatedEvent(
    @JsonProperty("category_id") String categoryId,
    String name,
    String slug,
    @JsonProperty("parent_id") String parentId,
    Integer level,
    @JsonProperty("is_leaf") Boolean isLeaf,
    String path
) {}

package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for {@code CATEGORY_UPDATED} events consumed from {@code t.category.events}.
 *
 * <p>Carries the new name and path for a category. The worker updates both
 * the category document and propagates the changes to all SKU documents
 * belonging to that category via {@code _update_by_query}.
 *
 * <p>Structural tree fields (slug, parent_id, level, is_leaf) are considered
 * immutable after creation. If those need to change, a Full Reindex must be
 * triggered manually.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryUpdatedEvent(
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("new_name") String newName,
    @JsonProperty("new_path") String newPath
) {}

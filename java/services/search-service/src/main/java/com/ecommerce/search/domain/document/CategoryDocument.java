package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A node of the {@code categories} index, {@code _id = category_id}.
 *
 * <p>{@code ancestorIds}, {@code ancestorSlugs}, {@code level} and {@code isLeaf} are
 * materialised from the Category snapshot Product publishes; Search does not own the tree
 * algorithm (ADR-013).
 *
 * <p>{@code productCount} and {@code productCountUpdatedAt} are null until the recompute
 * job has run at least once for this node (ADR-008).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryDocument(
        String categoryId,
        String name,
        String slug,
        String parentId,
        List<String> ancestorIds,
        List<String> ancestorSlugs,
        Integer level,
        Boolean isLeaf,
        List<FacetDefinition> facetDefinitions,

        Long productCount,
        Instant productCountUpdatedAt,

        Boolean isDeleted,
        Instant deletedAt,
        Instant updatedAt,
        Instant categoryIndexedAt,

        Long categoryRevision,
        String lastEventId) {

    /** Descriptor Product publishes for one attribute mapped into the category (Product ADR-019). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacetDefinition(String code, String label, String type) {
    }

    public List<FacetDefinition> facetDefinitionsOrEmpty() {
        return facetDefinitions == null ? List.of() : facetDefinitions;
    }

    public long productCountOrZero() {
        return productCount == null ? 0L : productCount;
    }

    public int levelOrZero() {
        return level == null ? 0 : level;
    }
}

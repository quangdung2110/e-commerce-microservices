package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * Response DTO for {@code GET /api/v1/search/facets/{categoryId}}.
 *
 * <p>Contains the available filter groups (brands, price ranges, dynamic
 * attributes) and their document counts for a given category.
 * All counts use DISTINCT_PRODUCT semantics.
 */
@Builder
public record FacetResponse(
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("count_semantics") String countSemantics,
    List<FilterGroup> filters,
    @JsonProperty("total_products") Long totalProducts
) {

    @Builder
    public record FilterGroup(
        String name,
        String type,
        List<FilterOption> options
    ) {}

    @Builder
    public record FilterOption(
        String value,
        String label,
        Long count
    ) {}
}

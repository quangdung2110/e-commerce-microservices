package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for {@code GET /api/v1/search/products}.
 *
 * <p>Contains paginated search results with optional facet aggregations.
 * Uses cursor-based pagination — {@code nextCursor} is {@code null} when
 * the last page has been reached.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductSearchResponse(
    Long total,
    @JsonProperty("total_skus") Long totalSkus,
    @JsonProperty("total_products") Long totalProducts,
    Integer size,
    List<ProductSearchItem> items,
    @JsonProperty("next_cursor") String nextCursor,
    FacetResponse facets,
    @JsonProperty("pagination_note") String paginationNote
) {}

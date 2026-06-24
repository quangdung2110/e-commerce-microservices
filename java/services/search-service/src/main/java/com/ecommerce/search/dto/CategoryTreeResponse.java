package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * Response DTO for {@code GET /api/v1/search/categories}.
 *
 * <p>Returns a flat list of category nodes with {@code parentId} references,
 * from which the client can reconstruct the tree. Each node includes
 * a {@code productCount} that reflects the number of ACTIVE products
 * in that category (eventually consistent, ±1-2 tolerance).
 */
@Builder
public record CategoryTreeResponse(List<CategoryNode> categories) {

    @Builder
    public record CategoryNode(
        @JsonProperty("category_id") String categoryId,
        String name,
        String slug,
        @JsonProperty("parent_id") String parentId,
        Integer level,
        @JsonProperty("is_leaf") Boolean isLeaf,
        String path,
        @JsonProperty("product_count") Long productCount
    ) {}
}

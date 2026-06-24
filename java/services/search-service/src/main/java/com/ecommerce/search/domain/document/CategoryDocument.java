package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;

/**
 * Elasticsearch document mapping 1:1 with {@code categories_v1} index.
 *
 * <p>Each document represents a single category node in the category tree.
 * The {@code product_count} field is maintained in real-time via
 * {@code _update_by_query} triggered by product lifecycle events.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryDocument(
    @JsonProperty("category_id") String categoryId,
    String name,
    String slug,
    @JsonProperty("parent_id") String parentId,
    Integer level,
    @JsonProperty("is_leaf") Boolean isLeaf,
    String path,
    @JsonProperty("product_count") Long productCount,
    @JsonProperty("is_deleted") Boolean isDeleted,
    @JsonProperty("deleted_at") Instant deletedAt,
    Instant version
) {}

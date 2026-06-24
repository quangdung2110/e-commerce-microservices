package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the Elasticsearch {@link SearchRequest} for the category browsing API
 * ({@code GET /api/v1/search/categories}).
 *
 * <p>Returns the full category tree (capped at 1000 entries) from the
 * {@code categories} alias. Categories are sorted hierarchically — first by
 * {@code level} (top-level first), then alphabetically by {@code category_id}
 * within the same level.
 *
 * <p>The response uses a flat list with {@code parent_id} references; the
 * client reconstructs the tree structure.
 */
@Component
@RequiredArgsConstructor
public class CategoryQueryBuilder {

    private final SearchProperties properties;

    /**
     * Build a search request that retrieves all visible categories.
     *
     * @return a fully-built Elasticsearch SearchRequest
     */
    public SearchRequest build() {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getCategories())
            .size(1000)
            .sort(List.of(
                SortOptions.of(so -> so.field(f -> f.field("level").order(SortOrder.Asc))),
                SortOptions.of(so -> so.field(f -> f.field("category_id").order(SortOrder.Asc)))
            ))
        );
    }
}

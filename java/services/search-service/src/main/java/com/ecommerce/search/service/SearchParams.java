package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Validated input parameters for the product search API
 * ({@code GET /api/v1/search/products}).
 *
 * <p>Immutably captures every query parameter after normalization and
 * validation. Use the static factory method {@link #of} to create an
 * instance with sensible defaults applied from {@link SearchProperties}.
 *
 * @param q             full-text search query ({@code null} → match_all)
 * @param category      category ID to filter by ({@code null} → no filter)
 * @param filters       dynamic attribute filters, key → list of values
 * @param priceMin      minimum best_price ({@code null} → no lower bound)
 * @param priceMax      maximum best_price ({@code null} → no upper bound)
 * @param inStock       {@code true} to only show in-stock items (default: true)
 * @param hasPromotion  {@code true} to only show promoted items ({@code null} → no filter)
 * @param sort          sort key (relevance/price_asc/price_desc/newest/rating)
 * @param cursor        base64-encoded {@code search_after} cursor ({@code null} → first page)
 * @param size          number of results per page (1–maxSize)
 */
public record SearchParams(
    String q,
    String category,
    Map<String, List<String>> filters,
    @Min(0) Long priceMin,
    @Min(0) Long priceMax,
    Boolean inStock,
    Boolean hasPromotion,
    @Pattern(regexp = "relevance|price_asc|price_desc|newest|rating",
             message = "sort must be one of: relevance, price_asc, price_desc, newest, rating")
    String sort,
    String cursor,
    @Min(1) int size
) {

    /**
     * Compact constructor that enforces a minimum page size.
     */
    public SearchParams {
        if (size <= 0) {
            size = 1;
        }
    }

    /**
     * Factory method that applies default values from application configuration
     * and validates the input.
     *
     * @param q             search query (may be {@code null})
     * @param category      category filter (may be {@code null})
     * @param filters       dynamic attribute filters (may be {@code null})
     * @param priceMin      minimum best_price (may be {@code null})
     * @param priceMax      maximum best_price (may be {@code null})
     * @param inStock       in-stock filter (may be {@code null} → defaults to true)
     * @param hasPromotion  promotion filter (may be {@code null})
     * @param sort          sort key (may be {@code null} → defaults to "relevance")
     * @param cursor        pagination cursor (may be {@code null})
     * @param size          requested page size (may be {@code null} → defaults from config)
     * @param properties    search configuration (used for default/max size)
     * @return a validated, normalized {@link SearchParams}
     */
    public static SearchParams of(
            String q, String category, Map<String, List<String>> filters,
            Long priceMin, Long priceMax, Boolean inStock, Boolean hasPromotion,
            String sort, String cursor, Integer size,
            SearchProperties properties) {

        int defaultSize = properties.getPagination().getDefaultSize();
        int maxSize = properties.getPagination().getMaxSize();
        int actualSize = (size == null) ? defaultSize : Math.min(size, maxSize);

        if (q != null) {
            q = q.trim().toLowerCase().replaceAll("\\s+", " ");
            if (q.isEmpty()) {
                q = null;
            }
        }

        return new SearchParams(
            q,
            category,
            filters != null && !filters.isEmpty() ? filters : null,
            priceMin, priceMax,
            inStock != null ? inStock : Boolean.TRUE,
            hasPromotion,
            sort != null ? sort : SortMapper.SORT_RELEVANCE,
            cursor,
            actualSize
        );
    }
}

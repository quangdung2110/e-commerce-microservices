package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.RangeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.FacetResponse;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for the facet API ({@code GET /api/v1/search/facets/{categoryId}}).
 *
 * <p>Returns all available filters (brands, price ranges, dynamic attributes)
 * and their product counts for a specific category. The dynamic attribute
 * facets are derived from the {@code searchable_attributes} and
 * {@code variant_attributes} composite keyword tokens.
 *
 * <p>The frontend groups the returned tokens by their {@code key:} prefix
 * to render dynamic filter groups — no backend changes needed when new
 * attribute types are added.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FacetService {

    private static final String COUNT_SEMANTICS = "DISTINCT_PRODUCT";

    private final ProductSearchRepository productSearchRepository;
    private final FacetQueryBuilder facetQueryBuilder;
    private final CategoryRepository categoryRepository;

    /**
     * Get all available facet filters for the given category.
     *
     * <p>Validates that the category exists before querying.
     *
     * @param categoryId the category ID to scope facets to
     * @return a {@link FacetResponse} with grouped filters and product counts
     * @throws SearchException with {@link ErrorCode#CATEGORY_NOT_FOUND} if
     *         the category does not exist
     */
    public FacetResponse getFacets(String categoryId) {
        log.debug("Fetching facets for categoryId={}", categoryId);

        // 1. Validate category exists
        var category = categoryRepository.getById(categoryId);
        if (category == null) {
            log.warn("Category not found: {}", categoryId);
            throw new SearchException(ErrorCode.CATEGORY_NOT_FOUND,
                "Category not found: " + categoryId);
        }

        // 2. Build and execute aggregation query
        SearchRequest request = facetQueryBuilder.build(categoryId);
        SearchResponse<ProductDocument> response = productSearchRepository.search(request);

        // 3. Extract total products from hits (size=0 so this is the matching count)
        long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;

        // 4. Parse aggregations
        Map<String, Aggregate> aggs = response.aggregations();
        List<FacetResponse.FilterGroup> filters = new ArrayList<>();

        if (aggs != null && !aggs.isEmpty()) {
            // Dynamic attributes from both attribute stores
            filters.addAll(parseAttributeFacets(aggs));
            // Price range facets
            filters.addAll(parsePriceRanges(aggs));
        }

        return FacetResponse.builder()
            .categoryId(categoryId)
            .countSemantics(COUNT_SEMANTICS)
            .filters(filters.isEmpty() ? null : filters)
            .totalProducts(totalHits)
            .build();
    }

    /**
     * Parse dynamic attribute facets from {@code searchable_attributes} and
     * {@code variant_attributes} aggregations.
     *
     * <p>Each token has the format {@code key:value} (e.g. "thuonghieu:apple").
     * Tokens are grouped by their key prefix into separate filter groups.
     * Both attribute stores are merged into a single set of groups.
     */
    private List<FacetResponse.FilterGroup> parseAttributeFacets(Map<String, Aggregate> aggs) {
        // Group options by attribute key, preserving insertion order
        Map<String, List<FacetResponse.FilterOption>> grouped = new LinkedHashMap<>();

        // Parse searchable_attributes (product-level: "thuonghieu:apple", "ram:8GB")
        Aggregate searchableAgg = aggs.get("searchable_attributes");
        if (searchableAgg != null && searchableAgg.isSterms()) {
            for (StringTermsBucket bucket : searchableAgg.sterms().buckets().array()) {
                addToGroup(grouped, bucket);
            }
        }

        // Parse variant_attributes (SKU-level: "mausac:đen", "bonho:256GB")
        Aggregate variantAgg = aggs.get("variant_attributes");
        if (variantAgg != null && variantAgg.isSterms()) {
            for (StringTermsBucket bucket : variantAgg.sterms().buckets().array()) {
                addToGroup(grouped, bucket);
            }
        }

        // Convert grouped map to FilterGroup list
        List<FacetResponse.FilterGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<FacetResponse.FilterOption>> entry : grouped.entrySet()) {
            groups.add(FacetResponse.FilterGroup.builder()
                .name(entry.getKey())       // e.g. "thuonghieu", "mausac", "bonho"
                .type("CHECKBOX")
                .options(entry.getValue())
                .build());
        }

        return groups;
    }

    /**
     * Extract a {@code key:value} token from a terms bucket and add it to the
     * grouped map. The product count comes from the cardinality sub-aggregation.
     */
    private void addToGroup(Map<String, List<FacetResponse.FilterOption>> grouped,
                            StringTermsBucket bucket) {
        String token = bucket.key().stringValue();
        long productCount = extractCardinalityCount(bucket);

        String[] parts = token.split(":", 2);
        if (parts.length == 2) {
            String key = parts[0];
            String value = parts[1];
            grouped.computeIfAbsent(key, k -> new ArrayList<>())
                .add(FacetResponse.FilterOption.builder()
                    .value(token)       // raw token "thuonghieu:apple"
                    .label(value)       // display value "apple"
                    .count(productCount)
                    .build());
        }
    }

    /**
     * Parse price range facets from the {@code price_ranges} aggregation.
     *
     * <p>Returns a single filter group with type "RANGE" containing the
     * pre-defined price buckets (Dưới 5 triệu, 5-15 triệu, etc.).
     */
    private List<FacetResponse.FilterGroup> parsePriceRanges(Map<String, Aggregate> aggs) {
        List<FacetResponse.FilterGroup> groups = new ArrayList<>();
        List<FacetResponse.FilterOption> options = new ArrayList<>();

        Aggregate priceAgg = aggs.get("price_ranges");
        if (priceAgg != null && priceAgg.isRange()) {
            for (RangeBucket bucket : priceAgg.range().buckets().array()) {
                String label = bucket.key();
                long productCount = extractCardinalityCount(bucket);
                String value = formatRangeValue(bucket);

                options.add(FacetResponse.FilterOption.builder()
                    .value(value)
                    .label(label)
                    .count(productCount)
                    .build());
            }
        }

        if (!options.isEmpty()) {
            groups.add(FacetResponse.FilterGroup.builder()
                .name("Khoảng giá")
                .type("RANGE")
                .options(options)
                .build());
        }

        return groups;
    }

    /**
     * Format a range bucket's from/to values into a string like {@code "0-5000000"}.
     */
    private String formatRangeValue(RangeBucket bucket) {
        String from = bucket.from() != null ? String.format("%.0f", bucket.from()) : "";
        String to = bucket.to() != null ? String.format("%.0f", bucket.to()) : "";
        if (!from.isEmpty() && !to.isEmpty()) {
            return from + "-" + to;
        } else if (!from.isEmpty()) {
            return from + "-*";
        } else if (!to.isEmpty()) {
            return "*-" + to;
        }
        return bucket.key();
    }

    /**
     * Extract the product count from a cardinality sub-aggregation named
     * {@code product_count} on a {@link StringTermsBucket}.
     */
    private long extractCardinalityCount(StringTermsBucket bucket) {
        Aggregate cardinalityAgg = bucket.aggregations().get("product_count");
        if (cardinalityAgg != null && cardinalityAgg.isCardinality()) {
            return (long) cardinalityAgg.cardinality().value();
        }
        return bucket.docCount();
    }

    /**
     * Extract the product count from a cardinality sub-aggregation named
     * {@code product_count} on a {@link RangeBucket}.
     */
    private long extractCardinalityCount(RangeBucket bucket) {
        Aggregate cardinalityAgg = bucket.aggregations().get("product_count");
        if (cardinalityAgg != null && cardinalityAgg.isCardinality()) {
            return (long) cardinalityAgg.cardinality().value();
        }
        return (long) bucket.docCount();
    }
}

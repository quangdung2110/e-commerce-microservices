package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.RangeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHitsResult;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.FacetResponse;
import com.ecommerce.search.dto.ProductSearchItem;
import com.ecommerce.search.dto.ProductSearchResponse;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.util.CursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.util.StringUtils;

/**
 * Service for the product search API ({@code GET /api/v1/search/products}).
 *
 * <p>Orchestrates cursor-based pagination, full-text search, filtering, field
 * collapsing, and facet aggregation. This is the primary search endpoint
 * serving category browsing and product discovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ProductSearchRepository repository;
    private final ProductQueryBuilder queryBuilder;
    private final SortMapper sortMapper;
    private final CursorCodec cursorCodec;
    private final ObjectMapper objectMapper;
    private final SearchProperties properties;

    /**
     * Execute a product search with the given parameters.
     *
     * @param params the validated search parameters (query, filters, sort, pagination)
     * @return a {@link ProductSearchResponse} with results, facets, and next cursor
     * @throws InvalidCursorException if the provided cursor cannot be decoded
     * @throws SearchException on search failures or timeouts
     */
    public ProductSearchResponse search(SearchParams params) {
        log.debug("Searching products: q={}, category={}, sort={}, size={}, cursor={}",
            params.q(), params.category(), params.sort(), params.size(),
            params.cursor() != null ? "[present]" : null);

        // 1. Decode cursor if present
        Integer fromOffset = null;
        if (params.cursor() != null && !params.cursor().isBlank()) {
            try {
                fromOffset = cursorCodec.decode(params.cursor());
                log.debug("Decoded from offset: {}", fromOffset);
            } catch (InvalidCursorException e) {
                throw e; // preserve error code
            } catch (Exception e) {
                throw new InvalidCursorException("Invalid cursor: " + e.getMessage(), e);
            }
        }

        // 2. Build the ES query
        SearchRequest request = queryBuilder.build(params, fromOffset);

        // 3. Execute search with timeout handling
        SearchResponse<ProductDocument> response;
        try {
            response = repository.search(request);
        } catch (SearchException e) {
            if (e.getErrorCode() == ErrorCode.ELASTICSEARCH_UNAVAILABLE
                || e.getErrorCode() == ErrorCode.SEARCH_TIMEOUT) {
                throw e;
            }
            log.error("Product search failed", e);
            throw new SearchException(ErrorCode.SEARCH_TIMEOUT, "Search failed", e);
        }

        // 4. Transform hits to response items
        List<ProductSearchItem> items = response.hits().hits().stream()
            .map(this::toItem)
            .toList();

        // 5. Extract total counts
        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        // 6. Build next cursor using offset
        String nextCursor = buildNextCursor(response, params.size(), fromOffset);

        // 7. Extract facets from aggregations
        FacetResponse facets = extractFacets(params, response.aggregations());

        return ProductSearchResponse.builder()
            .total(total)
            .totalSkus(total)
            .totalProducts(total)
            .size(params.size())
            .items(items)
            .nextCursor(nextCursor)
            .facets(facets)
            .paginationNote("next_cursor = null ends pagination. Each item may include additional SKUs in matching_skus")
            .build();
    }

    /**
     * Transform an ES hit into a {@link ProductSearchItem}.
     *
     * <p>If the document source is null (e.g. for a hit with only metadata),
     * returns a minimal item with just the product ID.
     */
    private ProductSearchItem toItem(Hit<ProductDocument> hit) {
        ProductDocument doc = hit.source();
        if (doc == null) {
            return ProductSearchItem.builder()
                .productId(hit.id())
                .build();
        }

        int discountPct = DiscountCalculator.calculate(doc.originalPrice(), doc.bestPrice());

        // Extract matching_skus from field collapsing inner_hits
        List<ProductSearchItem.MatchingSku> matchingSkus = extractMatchingSkus(hit);

        return ProductSearchItem.builder()
            .productId(doc.productId())
            .skuId(doc.skuId())
            .name(doc.name())
            .thumbnailUrl(doc.thumbnailUrl())
            .originalPrice(doc.originalPrice())
            .bestPrice(doc.bestPrice())
            .discountPercentage(discountPct)
            .productRatingAvg(doc.productRatingAvg())
            .isInStock(doc.isInStock())
            .matchingSkus(matchingSkus.isEmpty() ? null : matchingSkus)
            .build();
    }

    /**
     * Extract matching SKUs from the {@code matching_skus} inner hits
     * (populated by Elasticsearch Field Collapsing).
     *
     * <p>Returns up to 3 additional SKUs per product, sorted by price ascending.
     */
    private List<ProductSearchItem.MatchingSku> extractMatchingSkus(Hit<ProductDocument> hit) {
        List<ProductSearchItem.MatchingSku> matchingSkus = new ArrayList<>();
        if (hit.innerHits() != null && hit.innerHits().containsKey("matching_skus")) {
            InnerHitsResult innerHitsResult = hit.innerHits().get("matching_skus");
            for (Hit<JsonData> innerHit : innerHitsResult.hits().hits()) {
                JsonData source = innerHit.source();
                if (source != null) {
                    try {
                        ProductDocument doc = objectMapper.readValue(source.toString(), ProductDocument.class);
                        matchingSkus.add(ProductSearchItem.MatchingSku.builder()
                            .skuId(doc.skuId())
                            .bestPrice(doc.bestPrice())
                            .variantAttributes(doc.variantAttributes())
                            .build());
                    } catch (Exception e) {
                        log.warn("Failed to deserialize inner_hit for product {}", hit.id(), e);
                    }
                }
            }
        }
        return matchingSkus;
    }

    /**
     * Build the next cursor using the current offset.
     *
     * <p>Returns {@code null} if there are fewer results than the page size
     * (indicating the last page has been reached).
     */
    private String buildNextCursor(SearchResponse<ProductDocument> response, int pageSize, Integer currentOffset) {
        List<Hit<ProductDocument>> hits = response.hits().hits();
        if (hits.isEmpty() || hits.size() < pageSize) {
            return null;
        }
        int nextOffset = (currentOffset == null ? 0 : currentOffset) + hits.size();
        return cursorCodec.encode(nextOffset);
    }

    /**
     * Extract facets from the search response aggregations.
     *
     * <p>Parses the same {@code categories} and {@code price_ranges}
     * aggregations that are built by {@link ProductQueryBuilder}.
     *
     * @param params the search params (used for category context)
     * @param aggs   the ES aggregations map from the response
     * @return a {@link FacetResponse} or {@code null} if no aggregations present
     */
    private FacetResponse extractFacets(SearchParams params, Map<String, Aggregate> aggs) {
        if (aggs == null || aggs.isEmpty()) {
            return null;
        }

        List<FacetResponse.FilterGroup> filters = new ArrayList<>();

        // Parse categories aggregation
        Aggregate categoriesAgg = aggs.get("categories");
        if (categoriesAgg != null && categoriesAgg.isSterms()) {
            List<FacetResponse.FilterOption> catOptions = new ArrayList<>();
            for (StringTermsBucket bucket : categoriesAgg.sterms().buckets().array()) {
                String name = bucket.key().stringValue();
                long productCount = extractCardinalityCount(bucket);
                catOptions.add(FacetResponse.FilterOption.builder()
                    .value(name)
                    .label(name)
                    .count(productCount)
                    .build());
            }
            if (!catOptions.isEmpty()) {
                filters.add(FacetResponse.FilterGroup.builder()
                    .name("categories")
                    .type("CHECKBOX")
                    .options(catOptions)
                    .build());
            }
        }

        // Parse price ranges aggregation
        Aggregate priceAgg = aggs.get("price_ranges");
        if (priceAgg != null && priceAgg.isRange()) {
            List<FacetResponse.FilterOption> priceOptions = new ArrayList<>();
            for (RangeBucket bucket : priceAgg.range().buckets().array()) {
                String label = bucket.key();
                long productCount = extractCardinalityCount(bucket);
                String value = formatRangeValue(bucket);
                priceOptions.add(FacetResponse.FilterOption.builder()
                    .value(value)
                    .label(label)
                    .count(productCount)
                    .build());
            }
            if (!priceOptions.isEmpty()) {
                filters.add(FacetResponse.FilterGroup.builder()
                    .name("Khoảng giá")
                    .type("RANGE")
                    .options(priceOptions)
                    .build());
            }
        }

        // Parse dynamic attributes aggregation
        Aggregate attributesAgg = aggs.get("attributes");
        if (attributesAgg != null && attributesAgg.isSterms()) {
            Map<String, List<FacetResponse.FilterOption>> attributeGroups = new HashMap<>();
            for (StringTermsBucket bucket : attributesAgg.sterms().buckets().array()) {
                String term = bucket.key().stringValue();
                String[] parts = term.split(":", 2);
                if (parts.length == 2) {
                    String attrName = parts[0];
                    String attrValue = parts[1];
                    long productCount = extractCardinalityCount(bucket);
                    attributeGroups.computeIfAbsent(attrName, k -> new ArrayList<>())
                        .add(FacetResponse.FilterOption.builder()
                            .value(attrValue)
                            .label(StringUtils.capitalize(attrValue))
                            .count(productCount)
                            .build());
                }
            }
            
            for (Map.Entry<String, List<FacetResponse.FilterOption>> entry : attributeGroups.entrySet()) {
                filters.add(FacetResponse.FilterGroup.builder()
                    .name(entry.getKey()) // e.g. "brand"
                    .type("CHECKBOX")
                    .options(entry.getValue())
                    .build());
            }
        }

        if (filters.isEmpty()) {
            return null;
        }

        return FacetResponse.builder()
            .categoryId(params.category())
            .countSemantics("DISTINCT_PRODUCT")
            .filters(filters)
            .build();
    }

    /**
     * Format a range bucket's from/to into a string like "0-5000000".
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
     * Extract product count from a cardinality sub-aggregation named {@code product_count}
     * on a {@link StringTermsBucket}.
     */
    private long extractCardinalityCount(StringTermsBucket bucket) {
        Aggregate cardinalityAgg = bucket.aggregations().get("product_count");
        if (cardinalityAgg != null && cardinalityAgg.isCardinality()) {
            return (long) cardinalityAgg.cardinality().value();
        }
        return bucket.docCount();
    }

    /**
     * Extract product count from a cardinality sub-aggregation named {@code product_count}
     * on a {@link RangeBucket}.
     */
    private long extractCardinalityCount(RangeBucket bucket) {
        Aggregate cardinalityAgg = bucket.aggregations().get("product_count");
        if (cardinalityAgg != null && cardinalityAgg.isCardinality()) {
            return (long) cardinalityAgg.cardinality().value();
        }
        return bucket.docCount();
    }
}

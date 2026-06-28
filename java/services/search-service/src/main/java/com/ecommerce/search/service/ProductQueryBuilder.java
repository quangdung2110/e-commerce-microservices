package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.FieldCollapse;
import co.elastic.clients.elasticsearch.core.search.InnerHits;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Elasticsearch {@link SearchRequest} for the product search API
 * ({@code GET /api/v1/search/products}).
 *
 * <p>Constructs a complex bool query that supports:
 * <ul>
 *   <li>Full-text multi-match with field boosting (name, name.keyword, searchable_attributes)</li>
 *   <li>Standard filters (deleted status, product/sku status, stock, category, price range)</li>
 *   <li>Dynamic attribute filters that probe both {@code searchable_attributes} and
 *       {@code variant_attributes} via {@code bool/should} (no hardcoded attribute keys)</li>
 *   <li>Field collapsing on {@code product_id} with inner hits for matching SKUs</li>
 *   <li>Aggregations for category counts and price range facets</li>
 *   <li>Cursor-based pagination via {@code search_after}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductQueryBuilder {

    private final SortMapper sortMapper;
    private final SearchProperties properties;

    /**
     * Build a complete {@link SearchRequest} for the product search API.
     *
     * @param params      the validated and normalized search parameters
     * @param fromOffset  the decoded from offset from the cursor,
     *                    or {@code null}/{@code empty} for the first page
     * @return a fully-built Elasticsearch SearchRequest
     * @throws SearchException if query construction fails
     */
    public SearchRequest build(SearchParams params, Integer fromOffset) {
        try {
            Query boolQuery = buildBoolQuery(params);
            List<SortOptions> sortOptions = sortMapper.map(params.sort());

            SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(properties.getEs().getIndexAliases().getProducts())
                .query(boolQuery)
                .collapse(buildCollapse())
                .sort(sortOptions)
                .size(params.size())
                .trackTotalHits(t -> t.enabled(true));

            if (fromOffset != null) {
                builder.from(fromOffset);
            }

            builder.aggregations(buildAggregations());

            return builder.build();
        } catch (Exception e) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Failed to build product search query", e);
        }
    }

    /**
     * Build the bool query with must (full-text) and filter (structured) clauses.
     */
    private Query buildBoolQuery(SearchParams params) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        // ── Full-text (multi-match) ──────────────────────────────────────
        if (params.q() != null && !params.q().isBlank()) {
            must.add(Query.of(q -> q.multiMatch(mm -> mm
                .query(params.q())
                .fields("name^3", "name.keyword^2", "searchable_attributes")
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")
            )));
        } else {
            must.add(Query.of(q -> q.matchAll(m -> m)));
        }

        // ── Standard filters ─────────────────────────────────────────────
        // Only return documents that are sellable
        filter.add(Query.of(q -> q.term(t -> t.field("is_deleted").value(false))));
        filter.add(Query.of(q -> q.term(t -> t.field("product_status").value("ACTIVE"))));
        filter.add(Query.of(q -> q.term(t -> t.field("sku_status").value("ACTIVE"))));

        // In-stock filter (default: true — only show sellable items)
        if (Boolean.TRUE.equals(params.inStock())) {
            filter.add(Query.of(q -> q.term(t -> t.field("is_in_stock").value(true))));
        }

        // Category filter
        if (params.category() != null) {
            filter.add(Query.of(q -> q.term(t -> t.field("category_id").value(params.category()))));
        }

        // Price range filter
        if (params.priceMin() != null || params.priceMax() != null) {
            filter.add(Query.of(q -> q.range(r -> {
                r.field("best_price");
                if (params.priceMin() != null) {
                    r.gte(JsonData.of(params.priceMin()));
                }
                if (params.priceMax() != null) {
                    r.lte(JsonData.of(params.priceMax()));
                }
                return r;
            })));
        }

        // Promotion filter
        if (Boolean.TRUE.equals(params.hasPromotion())) {
            filter.add(Query.of(q -> q.term(t -> t.field("has_promotion").value(true))));
        }

        // ── Dynamic attribute filters ────────────────────────────────────
        // Each filter value matches against BOTH searchable_attributes (product-level)
        // AND variant_attributes (SKU-level) via bool/should.
        // This avoids hardcoding attribute keys and works with any category.
        if (params.filters() != null) {
            params.filters().forEach((key, values) -> {
                values.forEach(v -> {
                    String termValue = key + ":" + v;
                    filter.add(Query.of(q -> q.bool(b -> b
                        .minimumShouldMatch("1")
                        .should(s -> s.term(t -> t.field("searchable_attributes").value(termValue)))
                        .should(s -> s.term(t -> t.field("variant_attributes").value(termValue)))
                    )));
                });
            });
        }

        return Query.of(q -> q.bool(b -> b.must(must).filter(filter)));
    }

    /**
     * Build field collapsing on {@code product_id} with inner hits.
     *
     * <p>Each collapsed group returns the best-matching SKU for a product.
     * The inner hits provide up to 3 additional SKUs (sorted by price ascending)
     * so the client can show variant options without a second query.
     */
    private FieldCollapse buildCollapse() {
        return FieldCollapse.of(fc -> fc
            .field("product_id")
            .innerHits(InnerHits.of(ih -> ih
                .name("matching_skus")
                .size(3)
                .sort(List.of(
                    SortOptions.of(s -> s.field(f -> f.field("best_price").order(SortOrder.Asc))),
                    SortOptions.of(s -> s.field(f -> f.field("sku_id").order(SortOrder.Asc)))
                ))
            ))
        );
    }

    /**
     * Build aggregations for the search response.
     *
     * <p><b>Categories:</b> terms aggregation on {@code category_id} with
     * a cardinality sub-aggregation on {@code product_id} to count distinct
     * products (necessary because the index is SKU-first).
     *
     * <p><b>Price ranges:</b> pre-defined range buckets covering common
     * price brackets.
     */
    private Map<String, Aggregation> buildAggregations() {
        Map<String, Aggregation> aggs = new HashMap<>();

        aggs.put("categories", Aggregation.of(a -> a
            .terms(t -> t.field("category_id").size(20))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        aggs.put("price_ranges", Aggregation.of(a -> a
            .range(r -> r.field("best_price").ranges(List.of(
                AggregationRange.of(b -> b.to("5000000").key("Dưới 5 triệu")),
                AggregationRange.of(b -> b.from("5000000").to("15000000").key("5 - 15 triệu")),
                AggregationRange.of(b -> b.from("15000000").to("30000000").key("15 - 30 triệu")),
                AggregationRange.of(b -> b.from("30000000").key("Trên 30 triệu"))
            )))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        aggs.put("attributes", Aggregation.of(a -> a
            .terms(t -> t.field("searchable_attributes").size(100))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        return aggs;
    }
}

package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Elasticsearch {@link SearchRequest} for the facet API
 * ({@code GET /api/v1/search/facets/{categoryId}}).
 *
 * <p>Returns aggregation results only (size=0) — the actual product hits
 * are not fetched. Facets include:
 * <ul>
 *   <li><b>Dynamic attribute facets</b> — terms aggregations on both
 *       {@code searchable_attributes} and {@code variant_attributes} fields.
 *       The frontend groups tokens by their {@code key:} prefix to render
 *       filter groups (brand, RAM, color, etc.) dynamically.</li>
 *   <li><b>Price range facets</b> — pre-defined range buckets.</li>
 * </ul>
 *
 * <p>All aggregations include a {@code cardinality} sub-aggregation on
 * {@code product_id} to count distinct products (required because the
 * index uses a SKU-first strategy).
 */
@Component
@RequiredArgsConstructor
public class FacetQueryBuilder {

    private final SearchProperties properties;

    /**
     * Build a search request that returns only facet aggregations for a
     * given category.
     *
     * @param categoryId the category ID to scope the facets to
     * @return a fully-built Elasticsearch SearchRequest with size=0
     */
    public SearchRequest build(String categoryId) {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getProducts())
            .size(0)
            .trackTotalHits(t -> t.enabled(true))
            .query(Query.of(q -> q.bool(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(false))),
                    Query.of(qf -> qf.term(t -> t.field("product_status").value("ACTIVE"))),
                    Query.of(qf -> qf.term(t -> t.field("category_id").value(categoryId)))
                )
            )))
            .aggregations(buildAggregations())
        );
    }

    /**
     * Build the facet aggregations.
     *
     * <p>Two terms aggregations cover both attribute stores:
     * <ul>
     *   <li>{@code searchable_attributes} — product-level attributes
     *       (e.g. "thuonghieu:apple", "ram:8GB")</li>
     *   <li>{@code variant_attributes} — SKU-level attributes
     *       (e.g. "mausac:đen", "bonho:256GB")</li>
     * </ul>
     *
     * <p>Tokens follow the format {@code key:value}. The frontend splits on the
     * first colon to group options by key and render filter groups dynamically
     * — no backend changes needed when new attribute types are added.
     */
    private Map<String, Aggregation> buildAggregations() {
        Map<String, Aggregation> aggs = new HashMap<>();

        // Product-level attribute tokens (e.g. "thuonghieu:apple", "ram:8GB")
        aggs.put("searchable_attributes", Aggregation.of(a -> a
            .terms(t -> t.field("searchable_attributes").size(50))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        // SKU-level attribute tokens (e.g. "mausac:đen", "bonho:256GB")
        aggs.put("variant_attributes", Aggregation.of(a -> a
            .terms(t -> t.field("variant_attributes").size(50))
            .aggregations("product_count", Aggregation.of(sub -> sub
                .cardinality(c -> c.field("product_id").precisionThreshold(40000))
            ))
        ));

        // Price ranges
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

        return aggs;
    }
}

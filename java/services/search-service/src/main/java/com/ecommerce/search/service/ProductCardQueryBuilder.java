package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortMode;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.SearchQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ADR-020 product query over {@code product_cards} (ADR-027), answered natively by
 * Elasticsearch instead of grouping every matching SKU in Search.
 *
 * <p>Each rule keeps its meaning because every SKU condition sits in one nested query over
 * {@code skus}, so a single SKU must satisfy all of them:
 * <ul>
 *   <li>a Product matches when one of its SKUs matches;</li>
 *   <li>the representative is that Product's cheapest matching SKU, tie {@code sku_id asc}
 *       ({@code inner_hits});</li>
 *   <li>{@code price_asc}/{@code price_desc} order by that representative price (nested sort
 *       {@code mode: min} filtered by the same query), {@code newest} and {@code rating} by the
 *       highest value among matching SKUs ({@code mode: max}, a missing rating counting as 0),
 *       {@code relevance} by the best-scoring matching SKU ({@code score_mode: max}); every sort
 *       ends with {@code product_id asc};</li>
 *   <li>facet counts are distinct Products over matching SKUs ({@code reverse_nested}).</li>
 * </ul>
 */
@Component
public class ProductCardQueryBuilder {

    public static final String SKUS = "skus";
    public static final String REPRESENTATIVE = "representative";
    static final String PREFIX = SKUS + ".";
    static final String FACETS = "facets";
    static final String MATCHING = "matching";
    static final String TOKENS = "tokens";
    static final String PRODUCTS = "products";

    private final ProductQueryBuilder skuQueries;
    private final SearchProperties properties;

    public ProductCardQueryBuilder(ProductQueryBuilder skuQueries, SearchProperties properties) {
        this.skuQueries = skuQueries;
        this.properties = properties;
    }

    /** The SKU conditions of one request, over the nested SKU fields. */
    public Query skuQuery(SearchQuery request, Optional<String> categoryId) {
        return skuQueries.build(request, categoryId, PREFIX);
    }

    public Query query(Query skuQuery) {
        return Query.of(q -> q.nested(nested -> nested
                .path(SKUS)
                .query(skuQuery)
                .scoreMode(ChildScoreMode.Max)
                .innerHits(hits -> hits
                        .name(REPRESENTATIVE)
                        .size(1)
                        .sort(field(PREFIX + "selling_price_vnd", SortOrder.Asc), field(PREFIX + "sku_id", SortOrder.Asc)))));
    }

    public List<SortOptions> sort(SearchQuery request, Query skuQuery) {
        SortOptions primary = switch (request.sort()) {
            case RELEVANCE -> SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc)));
            case PRICE_ASC -> nested("selling_price_vnd", SortMode.Min, SortOrder.Asc, skuQuery, null);
            case PRICE_DESC -> nested("selling_price_vnd", SortMode.Min, SortOrder.Desc, skuQuery, null);
            case NEWEST -> nested("updated_at", SortMode.Max, SortOrder.Desc, skuQuery, null);
            case RATING -> nested("product_rating_avg", SortMode.Max, SortOrder.Desc, skuQuery, FieldValue.of(0));
        };
        return List.of(primary, field("product_id", SortOrder.Asc));
    }

    public Map<String, Aggregation> facets(Query skuQuery) {
        int size = Math.min(10_000, properties.query().facetMaxAttributes() * properties.query().facetMaxOptions());
        Aggregation tokens = Aggregation.of(a -> a
                .terms(terms -> terms.field(PREFIX + "filter_tokens").size(size))
                .aggregations(PRODUCTS, sub -> sub.reverseNested(reverse -> reverse)));
        Aggregation matching = Aggregation.of(a -> a.filter(skuQuery).aggregations(TOKENS, tokens));
        return Map.of(FACETS, Aggregation.of(a -> a.nested(nested -> nested.path(SKUS)).aggregations(MATCHING, matching)));
    }

    /** Distinct-Product count per token, in bucket order, from the {@link #facets} result. */
    public static List<Map.Entry<String, Long>> facetCounts(Map<String, Aggregate> aggregations) {
        List<Map.Entry<String, Long>> counts = new ArrayList<>();
        Aggregate facets = aggregations == null ? null : aggregations.get(FACETS);
        if (facets == null || !facets.isNested()) {
            return counts;
        }
        Aggregate matching = facets.nested().aggregations().get(MATCHING);
        Aggregate tokens = matching == null ? null : matching.filter().aggregations().get(TOKENS);
        if (tokens == null || !tokens.isSterms()) {
            return counts;
        }
        for (StringTermsBucket bucket : tokens.sterms().buckets().array()) {
            Aggregate products = bucket.aggregations().get(PRODUCTS);
            counts.add(Map.entry(bucket.key().stringValue(),
                    products != null && products.isReverseNested() ? products.reverseNested().docCount() : 0L));
        }
        return counts;
    }

    private static SortOptions nested(String field, SortMode mode, SortOrder order, Query skuQuery, FieldValue missing) {
        return SortOptions.of(s -> s.field(f -> {
            f.field(PREFIX + field).order(order).mode(mode).nested(n -> n.path(SKUS).filter(skuQuery));
            if (missing != null) {
                f.missing(missing);
            }
            return f;
        }));
    }

    private static SortOptions field(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }
}

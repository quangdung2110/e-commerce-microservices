package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.AttributeToken;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Filters SKU documents before grouping products and selecting representatives (ADR-020).
 */
@Component
public class ProductQueryBuilder {

    /** Aggregation name of the attribute facet terms bucket. */
    public static final String FACET_AGG = "attribute_tokens";

    /** Sub-aggregation name carrying the DISTINCT product count of a bucket. */
    public static final String DISTINCT_PRODUCTS_AGG = "distinct_products";

    private final SearchProperties properties;
    private final com.ecommerce.search.config.StorefrontProperties storefront;

    public ProductQueryBuilder(SearchProperties properties) {
        this(properties, new com.ecommerce.search.config.StorefrontProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductQueryBuilder(SearchProperties properties, com.ecommerce.search.config.StorefrontProperties storefront) {
        this.properties = properties;
        this.storefront = storefront;
    }

    /**
     * Full buyer query: mandatory visibility filters, buyer filters and, when {@code q} is
     * present, a fuzzy {@code multi_match} over the Vietnamese-analysed fields.
     */
    public Query build(SearchQuery query, Optional<String> resolvedCategoryId) {
        return build(query, resolvedCategoryId, "");
    }

    /**
     * The same query over SKU fields that live under {@code fieldPrefix}: {@code "skus."} for the
     * SKU documents nested in a {@code product_cards} document (ADR-027), so one SKU still has to
     * satisfy every condition at once.
     */
    public Query build(SearchQuery query, Optional<String> resolvedCategoryId, String fieldPrefix) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        visibilityFilters(query.inStock(), fieldPrefix).forEach(bool::filter);

        resolvedCategoryId.ifPresent(categoryId ->
                bool.filter(term(fieldPrefix + "category_ancestor_ids", categoryId)));

        attributeFilters(query.filterTokens(), fieldPrefix).forEach(bool::filter);

        if (query.priceMin().isPresent() || query.priceMax().isPresent()) {
            bool.filter(priceRange(query.priceMin(), query.priceMax(), fieldPrefix));
        }
        query.hasPromotion().ifPresent(flag ->
                bool.filter(Query.of(q -> q.term(t -> t.field(fieldPrefix + "has_promotion").value(flag)))));

        query.q().ifPresent(text -> bool.must(fullText(text, fieldPrefix)));

        return Query.of(q -> q.bool(bool.build()));
    }

    /**
     * The three filters the client can never turn off ({@code SEARCH-BR-001}), plus the
     * stock filter which defaults to on and the buyer may disable ({@code SEARCH-BR-002}).
     */
    public List<Query> visibilityFilters(boolean inStockOnly) {
        return visibilityFilters(inStockOnly, "");
    }

    private List<Query> visibilityFilters(boolean inStockOnly, String fieldPrefix) {
        List<Query> filters = new ArrayList<>(List.of(
                Query.of(q -> q.term(t -> t.field(fieldPrefix + "is_deleted").value(false))),
                term(fieldPrefix + "product_status", "ACTIVE"),
                term(fieldPrefix + "sku_status", "ACTIVE")));
        if (inStockOnly) {
            filters.add(Query.of(q -> q.term(t -> t.field(fieldPrefix + "is_in_stock").value(true))));
        }
        if (!storefront.getExcludedProductIds().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> b.mustNot(n -> n.terms(t -> t
                    .field(fieldPrefix + "product_id").terms(v -> v.value(storefront.getExcludedProductIds().stream().map(FieldValue::of).toList())))))));
        }
        if (!storefront.getExcludedCategoryIds().isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> b.mustNot(n -> n.terms(t -> t
                    .field(fieldPrefix + "category_ancestor_ids").terms(v -> v.value(storefront.getExcludedCategoryIds().stream().map(FieldValue::of).toList())))))));
        }
        return filters;
    }

    /**
     * Attribute filters: OR between values of the same attribute code, AND between
     * different codes ({@code WF-SEARCH-001} step 3).
     */
    private List<Query> attributeFilters(List<String> filterTokens, String fieldPrefix) {
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        for (String token : filterTokens) {
            String code = AttributeToken.decode(token).map(AttributeToken.Decoded::code).orElse(null);
            if (code != null) {
                byCode.computeIfAbsent(code, unused -> new ArrayList<>()).add(token);
            }
        }
        List<Query> filters = new ArrayList<>();
        byCode.values().forEach(tokens -> filters.add(Query.of(q -> q.terms(t -> t
                .field(fieldPrefix + "filter_tokens")
                .terms(values -> values.value(tokens.stream().map(FieldValue::of).toList()))))));
        return filters;
    }

    private Query priceRange(Optional<Long> min, Optional<Long> max, String fieldPrefix) {
        return Query.of(q -> q.range(range -> {
            range.field(fieldPrefix + "selling_price_vnd");
            min.ifPresent(value -> range.gte(co.elastic.clients.json.JsonData.of(value)));
            max.ifPresent(value -> range.lte(co.elastic.clients.json.JsonData.of(value)));
            return range;
        }));
    }

    /**
     * Vietnamese full text with {@code fuzziness: AUTO} so a one-character typo still
     * matches (ADR-003). {@code name.autocomplete} is the prefix fallback, weighted below
     * the analysed name so it never outranks a real term match.
     */
    private Query fullText(String text, String fieldPrefix) {
        return Query.of(q -> q.multiMatch(match -> match
                .query(text)
                .type(TextQueryType.BestFields)
                .fields(fieldPrefix + "name^4", fieldPrefix + "name.autocomplete^2", fieldPrefix + "description")
                .fuzziness("AUTO")
                .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)));
    }

    /** Composite visits every product, without shard-local top-N truncation. */
    public Aggregation productGroups(int batchSize, Map<String, FieldValue> after) {
        return Aggregation.of(a -> a.composite(c -> {
            c.size(batchSize).sources(List.of(Map.of("product", co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource.of(
                    s -> s.terms(t -> t.field("product_id"))))));
            if (after != null) c.after(after);
            return c;
        }).aggregations("representative", sub -> sub.topHits(t -> t.size(1)
                .source(s -> s.filter(f -> f.includes(List.of("sku_id", "product_id", "product_slug", "name",
                        "thumbnail_storage_key", "base_price_vnd", "selling_price_vnd", "product_rating_avg",
                        "product_rating_count", "is_in_stock"))))
                .sort(List.of(fieldSort("selling_price_vnd", SortOrder.Asc), fieldSort("sku_id", SortOrder.Asc)))))
                .aggregations("relevance", sub -> sub.max(m -> m.script(s -> s.inline(i -> i.source("_score")))))
                .aggregations("newest", sub -> sub.max(m -> m.field("updated_at")))
                .aggregations("rating", sub -> sub.max(m -> m.field("product_rating_avg"))));
    }

    /**
     * Attribute facet: one {@code terms} bucket per token with a {@code cardinality}
     * sub-aggregation on {@code product_id}, because every displayed count is a DISTINCT
     * product count and never a SKU {@code doc_count} ({@code SEARCH-INV-003}).
     */
    public Map<String, Aggregation> facetAggregations() {
        int size = Math.min(10_000, properties.query().facetMaxAttributes() * properties.query().facetMaxOptions());
        Aggregation distinctProducts = Aggregation.of(agg -> agg.cardinality(card -> card
                .field("product_id")
                .precisionThreshold(properties.query().cardinalityPrecisionThreshold())));
        return Map.of(FACET_AGG, Aggregation.of(agg -> agg
                .terms(terms -> terms.field("filter_tokens").size(size))
                .aggregations(DISTINCT_PRODUCTS_AGG, distinctProducts)));
    }

    private static SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }
}

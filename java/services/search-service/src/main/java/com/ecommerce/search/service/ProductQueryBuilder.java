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
 * Builds the Elasticsearch DSL for the storefront search.
 *
 * <p>Deliberately absent: {@code collapse} and {@code inner_hits}. Elasticsearch only
 * combines collapse with {@code search_after} when the sort field equals the collapse
 * field and there is no secondary sort, which is incompatible with a {@code sku_id}
 * tie-break on every sort — so a hit is a SKU and the same product may appear more than
 * once (ADR-012).
 */
@Component
public class ProductQueryBuilder {

    /** Aggregation name of the attribute facet terms bucket. */
    public static final String FACET_AGG = "attribute_tokens";

    /** Sub-aggregation name carrying the DISTINCT product count of a bucket. */
    public static final String DISTINCT_PRODUCTS_AGG = "distinct_products";

    private final SearchProperties properties;

    public ProductQueryBuilder(SearchProperties properties) {
        this.properties = properties;
    }

    /**
     * Full buyer query: mandatory visibility filters, buyer filters and, when {@code q} is
     * present, a fuzzy {@code multi_match} over the Vietnamese-analysed fields.
     */
    public Query build(SearchQuery query, Optional<String> resolvedCategoryId) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        visibilityFilters(query.inStock()).forEach(bool::filter);

        resolvedCategoryId.ifPresent(categoryId ->
                bool.filter(term("category_ancestor_ids", categoryId)));

        attributeFilters(query.filterTokens()).forEach(bool::filter);

        if (query.priceMin().isPresent() || query.priceMax().isPresent()) {
            bool.filter(priceRange(query.priceMin(), query.priceMax()));
        }
        query.hasPromotion().ifPresent(flag ->
                bool.filter(Query.of(q -> q.term(t -> t.field("has_promotion").value(flag)))));

        query.q().ifPresent(text -> bool.must(fullText(text)));

        return Query.of(q -> q.bool(bool.build()));
    }

    /**
     * The three filters the client can never turn off ({@code SEARCH-BR-001}), plus the
     * stock filter which defaults to on and the buyer may disable ({@code SEARCH-BR-002}).
     */
    public List<Query> visibilityFilters(boolean inStockOnly) {
        List<Query> filters = new ArrayList<>(List.of(
                Query.of(q -> q.term(t -> t.field("is_deleted").value(false))),
                term("product_status", "ACTIVE"),
                term("sku_status", "ACTIVE")));
        if (inStockOnly) {
            filters.add(Query.of(q -> q.term(t -> t.field("is_in_stock").value(true))));
        }
        return filters;
    }

    /**
     * Attribute filters: OR between values of the same attribute code, AND between
     * different codes ({@code WF-SEARCH-001} step 3).
     */
    private List<Query> attributeFilters(List<String> filterTokens) {
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        for (String token : filterTokens) {
            String code = AttributeToken.decode(token).map(AttributeToken.Decoded::code).orElse(null);
            if (code != null) {
                byCode.computeIfAbsent(code, unused -> new ArrayList<>()).add(token);
            }
        }
        List<Query> filters = new ArrayList<>();
        byCode.values().forEach(tokens -> filters.add(Query.of(q -> q.terms(t -> t
                .field("filter_tokens")
                .terms(values -> values.value(tokens.stream().map(FieldValue::of).toList()))))));
        return filters;
    }

    private Query priceRange(Optional<Long> min, Optional<Long> max) {
        return Query.of(q -> q.range(range -> {
            range.field("selling_price_vnd");
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
    private Query fullText(String text) {
        return Query.of(q -> q.multiMatch(match -> match
                .query(text)
                .type(TextQueryType.BestFields)
                .fields("name^4", "name.autocomplete^2", "description")
                .fuzziness("AUTO")
                .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)));
    }

    /**
     * Sort options for the requested ordering, always closed by the {@code sku_id asc}
     * tie-break ({@code SEARCH-BR-006}) that makes {@code search_after} deterministic.
     */
    public List<SortOptions> sortOptions(SortOption sort) {
        List<SortOptions> options = new ArrayList<>();
        switch (sort) {
            case RELEVANCE -> options.add(SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc))));
            case PRICE_ASC -> options.add(fieldSort("selling_price_vnd", SortOrder.Asc));
            case PRICE_DESC -> options.add(fieldSort("selling_price_vnd", SortOrder.Desc));
            case NEWEST -> options.add(fieldSort("updated_at", SortOrder.Desc));
            case RATING -> options.add(fieldSort("product_rating_avg", SortOrder.Desc));
        }
        options.add(fieldSort("sku_id", SortOrder.Asc));
        return options;
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

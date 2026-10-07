package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** DSL construction rules from {@code WF-SEARCH-001} and ADR-020. */
class ProductQueryBuilderTest {

    private final ProductQueryBuilder builder = new ProductQueryBuilder(properties());

    @Test
    void groupsProductsAndPicksTheCheapestMatchingSkuDeterministically() {
        var agg = builder.productGroups(500, null);
        assertThat(agg.composite().sources().getFirst().get("product").terms().field()).isEqualTo("product_id");
        var top = agg.aggregations().get("representative").topHits();
        assertThat(top.size()).isEqualTo(1);
        assertThat(top.sort().get(0).field().field()).isEqualTo("selling_price_vnd");
        assertThat(top.sort().get(1).field().field()).isEqualTo("sku_id");
        assertThat(agg.aggregations()).containsKeys("relevance", "newest", "rating");
    }

    @Test
    void alwaysAppliesTheThreeVisibilityFiltersTheClientCannotDisable() {
        String dsl = builder.build(query(true, List.of()), Optional.empty()).toString();

        assertThat(dsl).contains("is_deleted").contains("product_status").contains("sku_status");
    }

    @Test
    void filtersOnStockByDefaultAndDropsThatFilterWhenTheBuyerOptsOut() {
        assertThat(builder.visibilityFilters(true)).hasSize(4);
        assertThat(builder.visibilityFilters(false)).hasSize(3);
        assertThat(builder.build(query(false, List.of()), Optional.empty()).toString())
                .doesNotContain("is_in_stock");
    }

    @Test
    void neverUsesCollapseOrInnerHits() {
        // ADR-012: collapse is incompatible with search_after plus a secondary sort.
        String dsl = builder.build(query(true, List.of("color:s:black")), Optional.of("cat-1")).toString();

        assertThat(dsl).doesNotContain("collapse").doesNotContain("inner_hits");
    }

    @Test
    void groupsAttributeFiltersSoValuesOfOneCodeAreOredAndCodesAreAnded() {
        Query query = builder.build(
                query(true, List.of("color:s:black", "color:s:white", "brand:s:apple")), Optional.empty());

        // Two terms filters: one holding both colours, one holding the brand.
        long termsFilters = query.bool().filter().stream().filter(Query::isTerms).count();
        assertThat(termsFilters).isEqualTo(2);
    }

    @Test
    void filtersACategoryByAncestorIdRatherThanSlug() {
        // Slugs change; branch filtering must use ids (docs/search/data.md §4).
        assertThat(builder.build(query(true, List.of()), Optional.of("cat-1")).toString())
                .contains("category_ancestor_ids");
    }

    @Test
    void facetAggregationCountsDistinctProductsRatherThanDocuments() {
        var aggregations = builder.facetAggregations();

        assertThat(aggregations).containsKey(ProductQueryBuilder.FACET_AGG);
        assertThat(aggregations.get(ProductQueryBuilder.FACET_AGG).aggregations())
                .containsKey(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG);
        assertThat(aggregations.get(ProductQueryBuilder.FACET_AGG)
                .aggregations().get(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG)
                .cardinality().field()).isEqualTo("product_id");
    }

    @Test
    void appliesFuzzinessSoATypoStillMatches() {
        assertThat(builder.build(query(true, List.of()), Optional.empty()).toString())
                .contains("AUTO");
    }

    private static SearchQuery query(boolean inStock, List<String> filters) {
        return new SearchQuery(Optional.of("iphone"), Optional.empty(), filters,
                Optional.empty(), Optional.empty(), inStock, Optional.empty(),
                SortOption.RELEVANCE, 20, Optional.empty());
    }

    private static SearchProperties properties() {
        return new SearchProperties(
                new SearchProperties.Topics("t.product.events", "t.category.events",
                        "t.catalog.control.events", "t.inventory.stock.events.v1", "t.review.events"),
                new SearchProperties.Aliases("products", "products_write", "categories",
                        "categories_write", "search_suggestions", "search_jobs"),
                new SearchProperties.QueryBudget(Duration.ofSeconds(5), Duration.ofSeconds(2),
                        Duration.ofSeconds(5), Duration.ofMinutes(2), 1000, 50, 100),
                new SearchProperties.Pagination(20, 100),
                new SearchProperties.Bulk(1000, 5_242_880L, 4, 20, 0.5, 1.1, 20, 8,
                        Duration.ofMillis(500), Duration.ofSeconds(30), 300f),
                new SearchProperties.Reindex(Duration.ofHours(6), 500, 5,
                        Duration.ofSeconds(10), Duration.ofMinutes(30), 0.02),
                new SearchProperties.Suggestions("0 0 3 * * *", true, 1L, 0.8,
                        Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(1),
                        5000, 1000, 1.1, Long.MAX_VALUE, "vi", "GLOBAL", List.of()),
                new SearchProperties.Tombstone("0 30 2 * * *", true, Duration.ofDays(30),
                        "0 0 */4 * * *", true),
                new SearchProperties.Media("https://cdn.test/", "/static/placeholder.png"),
                new SearchProperties.Gateway("X-User-Id", "X-User-Roles", "X-Request-Id", "ADMIN"),
                new SearchProperties.Cors(List.of("*")),
                new SearchProperties.Export("http://product", "http://inventory", "http://review",
                        "review-token", true, Duration.ofSeconds(30), 2, 2));
    }
}

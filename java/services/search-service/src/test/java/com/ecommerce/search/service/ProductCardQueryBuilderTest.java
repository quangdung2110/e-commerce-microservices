package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortMode;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR-027: the ADR-020 rules expressed over nested SKU documents. */
class ProductCardQueryBuilderTest {

    private final SearchProperties properties = properties();
    private final ProductQueryBuilder skuQueries = new ProductQueryBuilder(properties);
    private final ProductCardQueryBuilder builder = new ProductCardQueryBuilder(skuQueries, properties);

    @Test
    void everySkuConditionSitsInOneNestedQuerySoOneSkuMustSatisfyThemAll() {
        SearchQuery request = query("iphone", SortOption.RELEVANCE, List.of("color:s:black"), true);
        Query sku = builder.skuQuery(request, Optional.of("cat-1"));

        String dsl = sku.toString();
        assertThat(dsl).contains("skus.is_deleted").contains("skus.product_status").contains("skus.sku_status")
                .contains("skus.is_in_stock").contains("skus.category_ancestor_ids").contains("skus.filter_tokens")
                .contains("skus.name^4").contains("skus.description");
        assertThat(dsl.replace("skus.", "")).isEqualTo(skuQueries.build(request, Optional.of("cat-1")).toString());

        var nested = builder.query(sku).nested();
        assertThat(nested.path()).isEqualTo("skus");
        assertThat(nested.scoreMode()).isEqualTo(ChildScoreMode.Max);
        assertThat(nested.innerHits().name()).isEqualTo(ProductCardQueryBuilder.REPRESENTATIVE);
        assertThat(nested.innerHits().size()).isEqualTo(1);
        assertThat(nested.innerHits().sort()).extracting(s -> s.field().field())
                .containsExactly("skus.selling_price_vnd", "skus.sku_id");
    }

    @Test
    void priceSortsUseTheCheapestMatchingSkuInBothDirections() {
        for (SortOption sort : List.of(SortOption.PRICE_ASC, SortOption.PRICE_DESC)) {
            Query sku = builder.skuQuery(query(null, sort, List.of(), true), Optional.empty());
            var primary = builder.sort(query(null, sort, List.of(), true), sku).get(0).field();

            assertThat(primary.field()).isEqualTo("skus.selling_price_vnd");
            assertThat(primary.mode()).isEqualTo(SortMode.Min);
            assertThat(primary.order()).isEqualTo(sort == SortOption.PRICE_ASC ? SortOrder.Asc : SortOrder.Desc);
            assertThat(primary.nested().path()).isEqualTo("skus");
            assertThat(primary.nested().filter()).isEqualTo(sku);
        }
    }

    @Test
    void newestAndRatingUseTheHighestMatchingValueAndAMissingRatingCountsAsZero() {
        SearchQuery newest = query(null, SortOption.NEWEST, List.of(), true);
        var byDate = builder.sort(newest, builder.skuQuery(newest, Optional.empty())).get(0).field();
        assertThat(byDate.field()).isEqualTo("skus.updated_at");
        assertThat(byDate.mode()).isEqualTo(SortMode.Max);
        assertThat(byDate.order()).isEqualTo(SortOrder.Desc);

        SearchQuery rating = query(null, SortOption.RATING, List.of(), true);
        var byRating = builder.sort(rating, builder.skuQuery(rating, Optional.empty())).get(0).field();
        assertThat(byRating.field()).isEqualTo("skus.product_rating_avg");
        assertThat(byRating.mode()).isEqualTo(SortMode.Max);
        assertThat(byRating.missing().isLong()).isTrue();
        assertThat(byRating.missing().longValue()).isZero();
    }

    @Test
    void everySortEndsWithProductIdAscending() {
        for (SortOption sort : SortOption.values()) {
            SearchQuery request = query(null, sort, List.of(), true);
            var sorts = builder.sort(request, builder.skuQuery(request, Optional.empty()));

            assertThat(sorts).hasSize(2);
            assertThat(sorts.get(1).field().field()).isEqualTo("product_id");
            assertThat(sorts.get(1).field().order()).isEqualTo(SortOrder.Asc);
        }
    }

    @Test
    void facetsCountDistinctProductsOverMatchingSkusOnly() {
        Query sku = builder.skuQuery(query(null, SortOption.RELEVANCE, List.of(), true), Optional.empty());
        var facets = builder.facets(sku).get("facets");

        assertThat(facets.nested().path()).isEqualTo("skus");
        var matching = facets.aggregations().get("matching");
        assertThat(matching.filter()).isEqualTo(sku);
        var tokens = matching.aggregations().get("tokens");
        assertThat(tokens.terms().field()).isEqualTo("skus.filter_tokens");
        assertThat(tokens.aggregations().get("products").isReverseNested()).isTrue();
    }

    @Test
    void readsTheReverseNestedCountsInBucketOrder() {
        Aggregate tokens = Aggregate.of(a -> a.sterms(s -> s.sumOtherDocCount(0L).buckets(b -> b.array(List.of(
                bucket("color:s:red", 9, 4), bucket("color:s:blue", 3, 2))))));
        Aggregate matching = Aggregate.of(a -> a.filter(f -> f.docCount(12).aggregations(Map.of("tokens", tokens))));
        Aggregate facets = Aggregate.of(a -> a.nested(n -> n.docCount(30).aggregations(Map.of("matching", matching))));

        assertThat(ProductCardQueryBuilder.facetCounts(Map.of("facets", facets)))
                .containsExactly(Map.entry("color:s:red", 4L), Map.entry("color:s:blue", 2L));
        assertThat(ProductCardQueryBuilder.facetCounts(Map.of())).isEmpty();
    }

    private static StringTermsBucket bucket(String token, long skus, long products) {
        Aggregate reverse = Aggregate.of(a -> a.reverseNested(r -> r.docCount(products)));
        return StringTermsBucket.of(b -> b.key(FieldValue.of(token)).docCount(skus).aggregations("products", reverse));
    }

    private static SearchQuery query(String q, SortOption sort, List<String> filters, boolean inStock) {
        return new SearchQuery(Optional.ofNullable(q), Optional.empty(), filters, Optional.empty(), Optional.empty(),
                inStock, Optional.empty(), sort, 20, Optional.empty());
    }

    private static SearchProperties properties() {
        SearchProperties properties = mock(SearchProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.query().facetMaxAttributes()).thenReturn(100);
        when(properties.query().facetMaxOptions()).thenReturn(50);
        return properties;
    }
}

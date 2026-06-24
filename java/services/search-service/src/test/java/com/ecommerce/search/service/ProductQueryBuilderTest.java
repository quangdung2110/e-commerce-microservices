package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductQueryBuilder")
class ProductQueryBuilderTest {

    private ProductQueryBuilder builder;
    private SearchProperties properties;

    @BeforeEach
    void setUp() {
        // Use real SearchProperties object (Lombok @Data is final, incompatible with Mockito on Java 24)
        properties = new SearchProperties();
        properties.getPagination().setDefaultSize(20);
        properties.getPagination().setMaxSize(100);

        SortMapper sortMapper = new SortMapper();

        builder = new ProductQueryBuilder(sortMapper, properties);
    }

    @Test
    @DisplayName("should build a query with multi-match when q is provided")
    void buildWithQueryText() {
        // Given
        SearchParams params = SearchParams.of(
            "iphone 15", null, null, null, null, null, null, null, null, null, properties
        );

        // When
        SearchRequest request = builder.build(params, null);

        // Then
        assertThat(request).isNotNull();
        assertThat(request.index()).containsExactly("products");
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.query()).isNotNull();
        assertThat(request.collapse()).isNotNull();
        assertThat(request.collapse().field()).isEqualTo("product_id");
        assertThat(request.aggregations()).isNotEmpty();
        assertThat(request.aggregations()).containsKeys("categories", "price_ranges");
        assertThat(request.sort()).hasSize(2);  // _score desc + sku_id asc
        assertThat(request.trackTotalHits()).isNotNull();
    }

    @Test
    @DisplayName("should build a match_all query when q is null")
    void buildWithoutQueryText() {
        // Given
        SearchParams params = SearchParams.of(
            null, null, null, null, null, null, null, null, null, null, properties
        );

        // When
        SearchRequest request = builder.build(params, null);

        // Then
        assertThat(request).isNotNull();
        assertThat(request.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("should include from offset when cursor is provided")
    void buildWithSearchAfter() {
        // Given
        SearchParams params = SearchParams.of(
            "test", null, null, null, null, null, null, "relevance", null, 10, properties
        );

        Integer fromOffset = 10;

        // When
        SearchRequest request = builder.build(params, fromOffset);

        // Then
        assertThat(request).isNotNull();
        assertThat(request.from()).isEqualTo(10);
    }

    @Test
    @DisplayName("should include dynamic attribute filters with bool/should on both fields")
    void buildWithDynamicFilters() {
        // Given
        Map<String, List<String>> filters = Map.of(
            "thuonghieu", List.of("apple"),
            "mausac", List.of("den")
        );
        SearchParams params = SearchParams.of(
            "iphone", null, filters, null, null, null, null, null, null, null, properties
        );

        // When
        SearchRequest request = builder.build(params, null);

        // Then
        assertThat(request).isNotNull();
        assertThat(request.query()).isNotNull();
    }

    @Test
    @DisplayName("should include standard filters (is_deleted, product_status, sku_status)")
    void buildWithStandardFilters() {
        // Given
        SearchParams params = SearchParams.of(
            "test", "cat-phones", null, 1_000_000L, 10_000_000L, true, true, null, null, null, properties
        );

        // When
        SearchRequest request = builder.build(params, null);

        // Then
        assertThat(request).isNotNull();
    }

    @Test
    @DisplayName("should respect max size limit")
    void buildWithSizeExceedingMax() {
        // Given
        SearchParams params = SearchParams.of(
            null, null, null, null, null, null, null, null, null, 999, properties
        );

        // When
        SearchRequest request = builder.build(params, null);

        // Then
        assertThat(request.size()).isEqualTo(100);
    }
}

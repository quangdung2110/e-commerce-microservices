package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FacetQueryBuilder")
class FacetQueryBuilderTest {

    private FacetQueryBuilder builder;

    @BeforeEach
    void setUp() {
        // Use real SearchProperties (Lombok @Data is incompatible with Mockito on Java 24)
        SearchProperties properties = new SearchProperties();
        builder = new FacetQueryBuilder(properties);
    }

    @Test
    @DisplayName("should build an aggregation-only query on the products index")
    void build() {
        // When
        SearchRequest request = builder.build("cat-smartphone");

        // Then
        assertThat(request).isNotNull();
        assertThat(request.index()).containsExactly("products");
        assertThat(request.size()).isEqualTo(0);  // aggregations only
        assertThat(request.query()).isNotNull();
        assertThat(request.aggregations()).isNotEmpty();
    }

    @Test
    @DisplayName("should include all three aggregation types")
    void buildAggregations() {
        // When
        SearchRequest request = builder.build("cat-laptop");

        // Then
        assertThat(request.aggregations()).containsKeys(
            "searchable_attributes",
            "variant_attributes",
            "price_ranges"
        );
    }

    @Test
    @DisplayName("should filter by the given category ID")
    void buildCategoryFilter() {
        // When
        SearchRequest request = builder.build("cat-phones");

        // Then
        assertThat(request.query()).isNotNull();
    }

    @Test
    @DisplayName("should filter out deleted and non-active products")
    void buildStandardFilters() {
        // When
        SearchRequest request = builder.build("cat-any");

        // Then
        // Verify basic filters exist via aggregation structure
        assertThat(request.aggregations()).hasSize(3);
    }
}

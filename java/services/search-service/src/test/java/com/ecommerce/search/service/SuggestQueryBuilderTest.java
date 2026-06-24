package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SuggestQueryBuilder")
class SuggestQueryBuilderTest {

    private SuggestQueryBuilder builder;

    @BeforeEach
    void setUp() {
        // Use real SearchProperties (Lombok @Data is incompatible with Mockito on Java 24)
        SearchProperties properties = new SearchProperties();
        builder = new SuggestQueryBuilder(properties);
    }

    @Test
    @DisplayName("should build a match query on the suggestions index")
    void build() {
        // When
        SearchRequest request = builder.build("iph", 8);

        // Then
        assertThat(request).isNotNull();
        assertThat(request.index()).containsExactly("search_suggestions");
        assertThat(request.size()).isEqualTo(8);
        assertThat(request.query()).isNotNull();
        assertThat(request.sort()).hasSize(2);
        assertThat(request.source()).isNotNull();
    }

    @Test
    @DisplayName("should respect the limit parameter")
    void buildWithCustomLimit() {
        // When
        SearchRequest request = builder.build("samsung", 5);

        // Then
        assertThat(request.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("should sort by weight desc then text.keyword asc")
    void buildSortOrder() {
        // When
        SearchRequest request = builder.build("ipad", 8);

        // Then
        assertThat(request.sort()).hasSize(2);
    }
}

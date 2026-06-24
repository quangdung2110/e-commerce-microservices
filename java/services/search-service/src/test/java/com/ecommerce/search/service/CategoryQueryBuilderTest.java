package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CategoryQueryBuilder")
class CategoryQueryBuilderTest {

    private CategoryQueryBuilder builder;

    @BeforeEach
    void setUp() {
        // Use real SearchProperties (Lombok @Data is incompatible with Mockito on Java 24)
        SearchProperties properties = new SearchProperties();
        builder = new CategoryQueryBuilder(properties);
    }

    @Test
    @DisplayName("should build a match-all query on the categories index")
    void build() {
        // When
        SearchRequest request = builder.build();

        // Then
        assertThat(request).isNotNull();
        assertThat(request.index()).containsExactly("categories");
        assertThat(request.size()).isEqualTo(1000);
        assertThat(request.sort()).hasSize(2);
    }

    @Test
    @DisplayName("should sort by level asc then category_id asc")
    void buildSortOrder() {
        // When
        SearchRequest request = builder.build();

        // Then
        assertThat(request.sort()).hasSize(2);
        String sortStr = request.sort().toString();
        assertThat(sortStr).contains("level");
        assertThat(sortStr).contains("category_id");
    }
}

package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SortMapper")
class SortMapperTest {

    private SortMapper sortMapper;

    @BeforeEach
    void setUp() {
        sortMapper = new SortMapper();
    }

    @ParameterizedTest
    @MethodSource("sortMappings")
    @DisplayName("should map sort parameter to correct sort options")
    void map(String sortParam, String expectedFirstField) {
        List<SortOptions> result = sortMapper.map(sortParam);
        assertThat(result).isNotEmpty();

        // First sort option should match the expected field
        String firstField = extractField(result.get(0));
        assertThat(firstField).isEqualTo(expectedFirstField);

        // Last option should always be the tie-breaker
        SortOptions last = result.get(result.size() - 1);
        String lastField = extractField(last);
        assertThat(lastField).isEqualTo("sku_id");
        assertThat(last.isField()).isTrue();
        assertThat(last.field().order()).isEqualTo(SortOrder.Asc);
    }

    @Test
    @DisplayName("should default to relevance when sort param is null")
    void defaultToRelevance() {
        List<SortOptions> result = sortMapper.map(null);
        assertThat(result).hasSize(2);
        // relevance → _score desc
        assertThat(result.get(0).isScore()).isTrue();
        assertThat(result.get(0).score().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    @DisplayName("should handle unknown sort gracefully by falling back to relevance")
    void unknownSortFallsBackToRelevance() {
        List<SortOptions> result = sortMapper.map("unknown_sort_value");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).isScore()).isTrue();
    }

    static Stream<Arguments> sortMappings() {
        return Stream.of(
            Arguments.of("relevance", "_score"),
            Arguments.of("price_asc", "best_price"),
            Arguments.of("price_desc", "best_price"),
            Arguments.of("newest", "updated_at"),
            Arguments.of("rating", "product_rating_avg")
        );
    }

    /**
     * Extract the field name from a SortOption, handling both field and score types.
     */
    private static String extractField(SortOptions option) {
        if (option.isField()) {
            return option.field().field();
        }
        if (option.isScore()) {
            return "_score";
        }
        return "unknown";
    }
}

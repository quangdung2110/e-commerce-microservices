package com.ecommerce.search.domain.model;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cursor encoding and the fingerprint check of ADR-007 / ADR-012. */
class SearchCursorTest {

    private final SearchCursor cursors = new SearchCursor(new ObjectMapper());

    @Test
    void roundTripsTheSortValuesOfTheLastHit() {
        SearchQuery query = query("iphone", SortOption.PRICE_ASC, List.of());
        List<FieldValue> sortValues = List.of(FieldValue.of(28_891_500L), FieldValue.of("sku-1"));

        List<FieldValue> decoded = cursors.decode(query, cursors.encode(query, sortValues));

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).longValue()).isEqualTo(28_891_500L);
        assertThat(decoded.get(1).stringValue()).isEqualTo("sku-1");
    }

    @Test
    void rejectsACursorReusedAfterTheSortChanged() {
        SearchQuery original = query("iphone", SortOption.PRICE_ASC, List.of());
        String cursor = cursors.encode(original, List.of(FieldValue.of("sku-1")));

        assertThatThrownBy(() -> cursors.decode(query("iphone", SortOption.RATING, List.of()), cursor))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).code())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    void rejectsACursorReusedAfterTheFiltersChanged() {
        SearchQuery original = query("iphone", SortOption.RELEVANCE, List.of("color:s:black"));
        String cursor = cursors.encode(original, List.of(FieldValue.of("sku-1")));

        assertThatThrownBy(() -> cursors.decode(
                query("iphone", SortOption.RELEVANCE, List.of("color:s:white")), cursor))
                .isInstanceOf(SearchException.class);
    }

    @Test
    void acceptsACursorWhenOnlyThePageSizeChanged() {
        SearchQuery twenty = query("iphone", SortOption.RELEVANCE, List.of());
        String cursor = cursors.encode(twenty, List.of(FieldValue.of("sku-1")));

        SearchQuery fifty = new SearchQuery(twenty.q(), twenty.category(), twenty.filterTokens(),
                twenty.priceMin(), twenty.priceMax(), twenty.inStock(), twenty.hasPromotion(),
                twenty.sort(), 50, Optional.of(cursor));

        assertThat(cursors.decode(fifty, cursor)).hasSize(1);
    }

    @Test
    void filterOrderDoesNotInvalidateACursor() {
        SearchQuery first = query("iphone", SortOption.RELEVANCE, List.of("a:s:1", "b:s:2"));
        String cursor = cursors.encode(first, List.of(FieldValue.of("sku-1")));

        assertThat(cursors.decode(query("iphone", SortOption.RELEVANCE, List.of("b:s:2", "a:s:1")), cursor))
                .hasSize(1);
    }

    @Test
    void rejectsAClientMintedCursor() {
        assertThatThrownBy(() -> cursors.decode(query("iphone", SortOption.RELEVANCE, List.of()), "not-a-cursor"))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).code())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    private static SearchQuery query(String q, SortOption sort, List<String> filters) {
        return new SearchQuery(Optional.of(q), Optional.empty(), filters, Optional.empty(), Optional.empty(),
                true, Optional.empty(), sort, 20, Optional.empty());
    }
}

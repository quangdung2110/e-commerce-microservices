package com.ecommerce.search.domain.model;

import com.ecommerce.search.domain.model.SearchCursor.Position;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cursor encoding and the fingerprint check of ADR-020. */
class SearchCursorTest {

    private final SearchCursor cursors = new SearchCursor(new ObjectMapper(), "unit-test-secret-at-least-thirty-two-characters");

    @Test
    void roundTripsSnapshotAndProductOffset() {
        SearchQuery query = query("iphone", SortOption.PRICE_ASC, List.of());
        Position sortValues = new Position("snapshot-1", 20);

        Position decoded = cursors.decode(query, cursors.encode(query, sortValues));

        assertThat(decoded.offset()).isEqualTo(20);
        assertThat(decoded.pitId()).isEqualTo("snapshot-1");

    }

    @Test
    void rejectsACursorReusedAfterTheSortChanged() {
        SearchQuery original = query("iphone", SortOption.PRICE_ASC, List.of());
        String cursor = cursors.encode(original, new Position("snapshot-1", 20));

        assertThatThrownBy(() -> cursors.decode(query("iphone", SortOption.RATING, List.of()), cursor))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).code())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    void rejectsACursorReusedAfterTheFiltersChanged() {
        SearchQuery original = query("iphone", SortOption.RELEVANCE, List.of("color:s:black"));
        String cursor = cursors.encode(original, new Position("snapshot-1", 20));

        assertThatThrownBy(() -> cursors.decode(
                query("iphone", SortOption.RELEVANCE, List.of("color:s:white")), cursor))
                .isInstanceOf(SearchException.class);
    }

    @Test
    void acceptsACursorWhenOnlyThePageSizeChanged() {
        SearchQuery twenty = query("iphone", SortOption.RELEVANCE, List.of());
        String cursor = cursors.encode(twenty, new Position("snapshot-1", 20));

        SearchQuery fifty = new SearchQuery(twenty.q(), twenty.category(), twenty.filterTokens(),
                twenty.priceMin(), twenty.priceMax(), twenty.inStock(), twenty.hasPromotion(),
                twenty.sort(), 50, Optional.of(cursor));

        assertThat(cursors.decode(fifty, cursor).offset()).isEqualTo(20);
    }

    @Test
    void filterOrderDoesNotInvalidateACursor() {
        SearchQuery first = query("iphone", SortOption.RELEVANCE, List.of("a:s:1", "b:s:2"));
        String cursor = cursors.encode(first, new Position("snapshot-1", 20));

        assertThat(cursors.decode(query("iphone", SortOption.RELEVANCE, List.of("b:s:2", "a:s:1")), cursor))
                .isEqualTo(new Position("snapshot-1", 20));
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

    @Test
    void rejectsTamperedPositionsAndLegacySkuCursors() {
        SearchQuery query = query("iphone", SortOption.RELEVANCE, List.of());
        String cursor = cursors.encode(query, new Position("snapshot-1", 20));
        assertThatThrownBy(() -> cursors.decode(query, "A" + cursor.substring(1))).isInstanceOf(SearchException.class);
        String legacy = java.util.Base64.getUrlEncoder().encodeToString("{\"v\":1,\"s\":[1,\"sku\"]}".getBytes());
        assertThatThrownBy(() -> cursors.decode(query, legacy)).isInstanceOf(SearchException.class);
    }

    @Test
    void preservesTheResolvedCategoryIdentityWithTheSnapshot() {
        SearchQuery query = query("iphone", SortOption.RELEVANCE, List.of());
        Position position = new Position("snapshot-1", 20, "category-1");
        assertThat(cursors.decode(query, cursors.encode(query, position))).isEqualTo(position);
    }
}

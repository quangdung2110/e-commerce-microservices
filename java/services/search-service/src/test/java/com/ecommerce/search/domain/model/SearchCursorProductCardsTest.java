package com.ecommerce.search.domain.model;

import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.domain.model.SearchCursor.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generation 3 cursors of the product_cards path (ADR-027) next to the generation 2 grouping cursors. */
class SearchCursorProductCardsTest {

    private final SearchCursor cursors = new SearchCursor(new ObjectMapper(), "unit-test-secret-at-least-thirty-two-characters");

    @Test
    void roundTripsTheSearchAfterValuesOfEveryKind() {
        SearchQuery query = query(SortOption.PRICE_ASC);
        List<Object> after = Arrays.asList(129_000L, "0190a7c2-product", 4.25, null, 7L);

        Position decoded = cursors.decode(query, cursors.encode(query, new Position("snapshot-1", 20, "cat-1", after)));

        assertThat(decoded.productCards()).isTrue();
        assertThat(decoded.pitId()).isEqualTo("snapshot-1");
        assertThat(decoded.offset()).isEqualTo(20);
        assertThat(decoded.categoryId()).isEqualTo("cat-1");
        assertThat(decoded.after()).hasSize(5);
        assertThat(((Number) decoded.after().get(0)).longValue()).isEqualTo(129_000L);
        assertThat(decoded.after().get(1)).isEqualTo("0190a7c2-product");
        assertThat(((Number) decoded.after().get(2)).doubleValue()).isEqualTo(4.25);
        assertThat(decoded.after().get(3)).isNull();
    }

    @Test
    void aGroupingCursorStaysOnTheGroupingPath() {
        SearchQuery query = query(SortOption.NEWEST);

        Position decoded = cursors.decode(query, cursors.encode(query, new Position("snapshot-1", 20)));

        assertThat(decoded.productCards()).isFalse();
        assertThat(decoded.after()).isNull();
    }

    @Test
    void aGeneration3CursorWithoutSortValuesIsRejected() throws Exception {
        SearchQuery query = query(SortOption.NEWEST);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(new ObjectMapper().writeValueAsBytes(
                java.util.Map.of("v", 3, "query", query.fingerprintSource(), "pitId", "snapshot-1", "offset", 20,
                        "after", List.of())));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "unit-test-secret-at-least-thirty-two-characters".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = body + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> cursors.decode(query, signed)).isInstanceOf(SearchException.class);
    }

    private static SearchQuery query(SortOption sort) {
        return new SearchQuery(Optional.of("iphone"), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                true, Optional.empty(), sort, 20, Optional.empty());
    }
}

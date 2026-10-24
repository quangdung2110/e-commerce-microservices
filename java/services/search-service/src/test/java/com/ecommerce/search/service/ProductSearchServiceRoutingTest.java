package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.SearchCursor;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductCardRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-027: which implementation answers a product search, and how its pages chain. */
class ProductSearchServiceRoutingTest {

    private final ProductSearchRepository grouping = mock(ProductSearchRepository.class);
    private final ProductCardRepository cards = mock(ProductCardRepository.class);
    private final ProductCardReadiness readiness = mock(ProductCardReadiness.class);
    private final SearchCursor cursors = new SearchCursor(new ObjectMapper(), "unit-test-secret-at-least-thirty-two-characters");
    private final ProductSearchService service = service();

    @Test
    void aNewSearchReadsTheProjectionWhenItMirrorsTheSkuIndex() throws IOException {
        when(readiness.ready()).thenReturn(true);
        when(cards.openSnapshot()).thenReturn("pit-cards");
        when(cards.search(any(), any(), any(), eq(1), eq("pit-cards"), isNull())).thenReturn(new ProductCardRepository.Page(
                List.of(card("p-1", 129_000L)), 3, Map.of(), "pit-cards-2"));

        SearchDtos.SearchPage page = service.search(query(1, null));

        assertThat(page.totalProducts()).isEqualTo(3);
        assertThat(page.items()).extracting(SearchDtos.SearchProductItem::productId).containsExactly("p-1");
        SearchCursor.Position next = cursors.decode(query(1, null), page.nextCursor());
        assertThat(next.productCards()).isTrue();
        assertThat(next.pitId()).isEqualTo("pit-cards-2");
        assertThat(next.offset()).isEqualTo(1);
        assertThat(next.after()).hasSize(2);
        verify(grouping, never()).openSnapshot();
        verify(cards, never()).closeSnapshot(anyString());
    }

    @Test
    void theNextPageContinuesFromTheLastSortValuesAndTheLastPageClosesTheSnapshot() throws IOException {
        String cursor = cursors.encode(query(1, null), new SearchCursor.Position("pit-cards", 2, null,
                List.of(129_000L, "p-2")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldValue>> after = ArgumentCaptor.forClass(List.class);
        when(cards.search(any(), any(), any(), eq(1), eq("pit-cards"), after.capture()))
                .thenReturn(new ProductCardRepository.Page(List.of(card("p-3", 149_000L)), 3, Map.of(), "pit-cards"));

        SearchDtos.SearchPage page = service.search(query(1, cursor));

        assertThat(after.getValue()).hasSize(2);
        assertThat(after.getValue().get(0).longValue()).isEqualTo(129_000L);
        assertThat(after.getValue().get(1).stringValue()).isEqualTo("p-2");
        assertThat(page.items()).extracting(SearchDtos.SearchProductItem::productId).containsExactly("p-3");
        assertThat(page.nextCursor()).isNull();
        verify(cards).closeSnapshot("pit-cards");
        verify(readiness, never()).ready();
    }

    @Test
    void aGroupingCursorStaysOnTheGroupingPathEvenWhenTheProjectionIsReady() throws IOException {
        when(readiness.ready()).thenReturn(true);
        String cursor = cursors.encode(query(1, null), new SearchCursor.Position("pit-skus", 1));
        when(grouping.products(any(), eq("pit-skus"))).thenReturn(
                new ProductSearchRepository.ProductSnapshot(List.of(), Map.of(), "pit-skus"));

        try {
            service.search(query(1, cursor));
        } catch (RuntimeException outsideTheResult) {
            // offset 1 is outside an empty grouping; only the routing matters here
        }

        verify(grouping).products(any(), eq("pit-skus"));
        verify(cards, never()).search(any(), any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void withoutTheProjectionANewSearchFailsQuicklyAndNeverScansSkus() throws IOException {
        when(readiness.ready()).thenReturn(false);
        when(grouping.openSnapshot()).thenReturn("pit-skus");
        when(grouping.products(any(), eq("pit-skus"))).thenReturn(
                new ProductSearchRepository.ProductSnapshot(List.of(), Map.of(), "pit-skus"));

        assertThatThrownBy(() -> service.search(query(20, null)))
                .isInstanceOfSatisfying(com.ecommerce.search.common.SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(com.ecommerce.search.common.ErrorCode.ELASTICSEARCH_UNAVAILABLE));
        verify(grouping, never()).openSnapshot();
        verify(cards, never()).openSnapshot();
    }

    private ProductSearchService service() {
        SearchProperties properties = mock(SearchProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.query().facetMaxAttributes()).thenReturn(100);
        when(properties.query().facetMaxOptions()).thenReturn(50);
        ProductQueryBuilder queries = new ProductQueryBuilder(properties);
        FacetLabelResolver labels = mock(FacetLabelResolver.class);
        when(labels.labels(any())).thenReturn(Map.of());
        ThumbnailUrlResolver thumbnails = mock(ThumbnailUrlResolver.class);
        when(thumbnails.resolve(any())).thenReturn("https://cdn.test/thumb.jpg");
        return new ProductSearchService(grouping, cards, mock(CategoryRepository.class), queries,
                new ProductCardQueryBuilder(queries, properties), readiness, new FacetAssembler(properties), labels,
                thumbnails, cursors, new StorefrontFacetPolicy(new com.ecommerce.search.config.StorefrontProperties(), mock(CategoryRepository.class)));
    }

    private static ProductCardRepository.Card card(String productId, long price) throws IOException {
        ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ProductDocument doc = json.readValue("""
                {"sku_id":"%s-sku","product_id":"%s","product_slug":"%s","name":"Áo","base_price_vnd":%d,
                 "selling_price_vnd":%d,"is_in_stock":true,"product_rating_count":0}""".formatted(
                productId, productId, productId, price, price), ProductDocument.class);
        return new ProductCardRepository.Card(doc, List.of(FieldValue.of(price), FieldValue.of(productId)));
    }

    private static SearchQuery query(int size, String cursor) {
        return new SearchQuery(Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                true, Optional.empty(), SortOption.PRICE_ASC, size, Optional.ofNullable(cursor));
    }
}

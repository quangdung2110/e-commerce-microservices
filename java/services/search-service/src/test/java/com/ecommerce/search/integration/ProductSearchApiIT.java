package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.ecommerce.search.domain.model.SortOption;
import com.ecommerce.search.domain.model.SearchCursor;
import com.ecommerce.search.domain.model.SearchQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code WF-SEARCH-001} end to end, over the real HTTP contract. */
@org.springframework.test.context.TestPropertySource(properties = "search.pagination.product-scan-batch-size=2")
class ProductSearchApiIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00.000Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProjectionWriter writer;

    @Autowired
    private BatchExecutor executor;

    @Autowired
    private SearchCursor cursors;

    @Test
    void returnsOneProductRepresentedByItsCheapestMatchingSku() throws IOException {
        // ADR-020: one Product card, cheapest eligible SKU.
        index("sku-1", "prod-1", "iPhone 15", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-2", "prod-1", "iPhone 15", "ACTIVE", "ACTIVE", true, 2_000_000L);

        SearchDtos.SearchPage page = search("?q=iphone");

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().skuId()).isEqualTo("sku-1");
        assertThat(page.items().getFirst().sellingPriceVnd()).isEqualTo(1_000_000L);
        assertThat(page.items()).allMatch(item -> item.productId().equals("prod-1"));
        assertThat(page.totalProducts()).isEqualTo(1);
    }

    @Test
    void neverExposesDraftHiddenInactiveOrDeletedRows() throws IOException {
        index("sku-active", "prod-1", "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-draft", "prod-2", "iPhone", "DRAFT", "ACTIVE", true, 1_000_000L);
        index("sku-hidden", "prod-3", "iPhone", "HIDDEN", "ACTIVE", true, 1_000_000L);
        index("sku-inactive", "prod-4", "iPhone", "ACTIVE", "INACTIVE", true, 1_000_000L);

        SearchDtos.SearchPage page = search("?q=iphone&in_stock=false");

        assertThat(page.items()).extracting(SearchDtos.SearchProductItem::skuId)
                .containsExactly("sku-active");
    }

    @Test
    void hidesOutOfStockByDefaultAndShowsItWhenTheBuyerAsks() throws IOException {
        index("sku-in", "prod-1", "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-out", "prod-2", "iPhone", "ACTIVE", "ACTIVE", false, 1_000_000L);

        assertThat(search("?q=iphone").items())
                .extracting(SearchDtos.SearchProductItem::skuId).containsExactly("sku-in");
        assertThat(search("?q=iphone&in_stock=false").items())
                .extracting(SearchDtos.SearchProductItem::skuId).containsExactlyInAnyOrder("sku-in", "sku-out");
    }

    @Test
    void findsVietnameseTextWithAndWithoutDiacritics() throws IOException {
        index("sku-1", "prod-1", "Điện thoại thông minh", "ACTIVE", "ACTIVE", true, 1_000_000L);

        assertThat(searchFor("dien thoai").items()).hasSize(1);
        assertThat(searchFor("Điện thoại").items()).hasSize(1);
    }

    @Test
    void paginatesThroughEveryProductWithoutRepeatingOrSkipping() throws IOException {
        for (int i = 0; i < 25; i++) {
            index("sku-" + String.format("%02d", i), "prod-" + i, "iPhone", "ACTIVE", "ACTIVE",
                    true, 1_000_000L + i);
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String suffix = cursor == null ? "" : "&cursor=" + cursor;
            SearchDtos.SearchPage page = search("?q=iphone&sort=price_asc&size=10" + suffix);
            page.items().forEach(item -> assertThat(seen.add(item.productId()))
                    .as("Product %s appeared twice", item.productId()).isTrue());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).hasSize(25);
    }

    @Test
    void rejectsACursorReplayedAgainstADifferentSort() throws IOException {
        for (int i = 0; i < 25; i++) {
            index("sku-" + i, "prod-" + i, "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L + i);
        }
        String cursor = search("?q=iphone&sort=price_asc&size=10").nextCursor();

        ResponseEntity<String> response = rest.getForEntity(
                "/api/v2/search/products?q=iphone&sort=rating&size=10&cursor=" + cursor, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_CURSOR");
    }

    @Test
    void facetCountsDistinctProductsRatherThanSkus() throws IOException {
        // SEARCH-INV-003: three SKUs of one product are one product in the facet count.
        index("sku-1", "prod-1", "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-2", "prod-1", "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-3", "prod-1", "iPhone", "ACTIVE", "ACTIVE", true, 1_000_000L);

        SearchDtos.SearchPage page = search("?q=iphone");

        assertThat(page.facets().countSemantics()).isEqualTo("DISTINCT_PRODUCT");
        assertThat(page.facets().filters())
                .filteredOn(facet -> facet.code().equals("brand"))
                .allSatisfy(facet -> assertThat(facet.options()).allSatisfy(
                        option -> assertThat(option.count()).isEqualTo(1)));
    }

    @Test
    void anEmptyResultIsASuccessfulResponse() {
        ResponseEntity<SearchDtos.SearchPage> response =
                rest.getForEntity("/api/v2/search/products?q=nothingmatchesthis", SearchDtos.SearchPage.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items()).isEmpty();
        assertThat(response.getBody().nextCursor()).isNull();
    }

    @Test
    void rejectsAnUnacceptableSizeWithProblemDetails() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v2/search/products?size=500", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).contains("INVALID_QUERY_PARAMETER").contains("\"retryable\":false");
    }

    @Test
    void rejectsAPriceRangeThatIsInverted() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v2/search/products?price_min=500&price_max=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("price_max");
    }

    @Test
    void suggestRejectsAOneCharacterPrefix() {
        ResponseEntity<String> response = rest.getForEntity("/api/v2/search/suggestions?q=a", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_QUERY_PARAMETER");
    }

    @Test
    void reindexIsRefusedWithoutTheAdminRole() {
        ResponseEntity<String> anonymous = rest.postForEntity(
                "/api/v2/search/reindex-runs", Map.of("reason", "mapping change"), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Passes {@code q} as a URI template variable so RestTemplate encodes it once. Writing
     * the percent-encoding by hand would be encoded a second time and the service would
     * search for the literal escape sequence.
     */
    private SearchDtos.SearchPage searchFor(String q) {
        ResponseEntity<SearchDtos.SearchPage> response = rest.getForEntity(
                "/api/v2/search/products?q={q}", SearchDtos.SearchPage.class, q);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private SearchDtos.SearchPage search(String queryString) {
        ResponseEntity<SearchDtos.SearchPage> response =
                rest.getForEntity("/api/v2/search/products" + queryString, SearchDtos.SearchPage.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void index(String skuId, String productId, String name, String productStatus, String skuStatus,
                       boolean isInStock, long sellingPrice) throws IOException {
        index(skuId, productId, name, productStatus, skuStatus, isInStock, sellingPrice, "Black");
    }

    private void index(String skuId, String productId, String name, String productStatus, String skuStatus,
                       boolean isInStock, long sellingPrice, String color) throws IOException {
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                productId, "cat-1", name, productId + "-slug", "description",
                Map.of("brand", "Apple"), productStatus, 1L, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "CODE-" + skuId, 2_000_000L, sellingPrice, skuStatus,
                Map.of("color", color), "media/" + skuId + ".jpg", NOW);

        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                1L, Timestamps.nowIso(), "catalog-" + skuId));
        batch.add(writer.availabilityPatch(batch.targetIndex(), skuId, isInStock, 1L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-" + skuId));
        executor.execute("t.product.events", batch);
        refreshAll();
    }

    @Test
    void priceDescendingSortUsesMinimumEligiblePriceRatherThanTheMostExpensiveSku() throws IOException {
        index("a-cheap", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("a-expensive", "a", "Phone", "ACTIVE", "ACTIVE", true, 9_000_000L);
        index("b-cheap", "b", "Phone", "ACTIVE", "ACTIVE", true, 2_000_000L);
        assertThat(search("?sort=price_desc").items()).extracting(SearchDtos.SearchProductItem::skuId)
                .containsExactly("b-cheap", "a-cheap");
    }

    @Test
    void representativeSatisfiesAllFiltersAndTiesUseSkuId() throws IOException {
        index("white", "a", "Phone", "ACTIVE", "ACTIVE", true, 500_000L, "White");
        index("black-b", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L, "Black");
        index("black-a", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L, "Black");
        index("black-out", "a", "Phone", "ACTIVE", "ACTIVE", false, 700_000L, "Black");
        assertThat(search("?filter=color:s:black").items().getFirst().skuId()).isEqualTo("black-a");
        assertThat(search("?filter=color:s:black&in_stock=false").items().getFirst().skuId()).isEqualTo("black-out");
        assertThat(search("?price_min=800000").items().getFirst().skuId()).isEqualTo("black-a");
    }

    @Test
    void snapshotPreventsUpdatesAndNewProductsFromChangingLaterPages() throws IOException {
        index("a-sku", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("b-sku", "b", "Phone", "ACTIVE", "ACTIVE", true, 2_000_000L);
        var first = search("?sort=price_asc&size=1");
        client.update(b -> b.index(properties.aliases().productsWrite()).id("b-sku")
                .doc(Map.of("selling_price_vnd", 100L)), Map.class);
        index("new-sku", "new", "Phone", "ACTIVE", "ACTIVE", true, 500_000L);
        var second = search("?sort=price_asc&size=1&cursor=" + first.nextCursor());
        assertThat(second.totalProducts()).isEqualTo(2);
        assertThat(second.items().getFirst().skuId()).isEqualTo("b-sku");
        assertThat(second.items().getFirst().sellingPriceVnd()).isEqualTo(2_000_000L);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void finalFullPageHasNoPhantomNextCursor() throws IOException {
        index("sku-1", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        var page = search("?size=1");
        assertThat(page.totalProducts()).isEqualTo(1);
        assertThat(page.nextCursor()).isNull();
    }

    @ParameterizedTest
    @EnumSource(SortOption.class)
    void everySortKeepsOneProductAcrossMultipleCompositeAndResponsePages(SortOption sort) throws IOException {
        for (int i = 0; i < 5; i++) {
            index("cheap-" + i, "prod-" + i, "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L + i);
            index("expensive-" + i, "prod-" + i, "Phone", "ACTIVE", "ACTIVE", true, 9_000_000L + i);
        }
        Set<String> products = new HashSet<>();
        String cursor = null;
        do {
            var page = search("?sort=" + sort.wireValue() + "&size=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.totalProducts()).isEqualTo(5);
            for (var item : page.items()) {
                assertThat(products.add(item.productId())).isTrue();
                assertThat(item.skuId()).startsWith("cheap-");
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(products).hasSize(5);
    }

    @Test
    void expiredSnapshotReturnsInvalidCursorRatherThanAPartialOrNewResultSet() throws IOException {
        index("a-sku", "a", "Phone", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("b-sku", "b", "Phone", "ACTIVE", "ACTIVE", true, 2_000_000L);
        var page = search("?sort=price_asc&size=1");
        var query = new SearchQuery(Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                true, Optional.empty(), SortOption.PRICE_ASC, 1, Optional.empty());
        var position = cursors.decode(query, page.nextCursor());
        client.closePointInTime(b -> b.id(position.pitId()));
        var response = rest.getForEntity("/api/v2/search/products?sort=price_asc&size=1&cursor=" + page.nextCursor(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_CURSOR");
    }
}

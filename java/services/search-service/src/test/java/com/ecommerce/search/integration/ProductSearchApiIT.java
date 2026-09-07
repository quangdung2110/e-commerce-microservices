package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code WF-SEARCH-001} end to end, over the real HTTP contract. */
class ProductSearchApiIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00.000Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProjectionWriter writer;

    @Autowired
    private BatchExecutor executor;

    @Test
    void returnsOneItemPerMatchingSkuIncludingSeveralFromTheSameProduct() throws IOException {
        // ADR-012: the API does not deduplicate by product; a card per SKU is intended.
        index("sku-1", "prod-1", "iPhone 15", "ACTIVE", "ACTIVE", true, 1_000_000L);
        index("sku-2", "prod-1", "iPhone 15", "ACTIVE", "ACTIVE", true, 2_000_000L);

        SearchDtos.SearchPage page = search("?q=iphone");

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allMatch(item -> item.productId().equals("prod-1"));
        assertThat(page.totalSkus()).isEqualTo(2);
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
    void paginatesThroughEverySkuWithoutRepeatingOrSkipping() throws IOException {
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
            page.items().forEach(item -> assertThat(seen.add(item.skuId()))
                    .as("SKU %s appeared twice", item.skuId()).isTrue());
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
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                productId, "cat-1", name, productId + "-slug", "description",
                Map.of("brand", "Apple"), productStatus, 1L, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "CODE-" + skuId, 2_000_000L, sellingPrice, skuStatus,
                Map.of("color", "Black"), "media/" + skuId + ".jpg", NOW);

        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                1L, Timestamps.nowIso(), "catalog-" + skuId));
        batch.add(writer.availabilityPatch(batch.targetIndex(), skuId, isInStock, 1L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-" + skuId));
        executor.execute("t.product.events", batch);
        refreshAll();
    }
}

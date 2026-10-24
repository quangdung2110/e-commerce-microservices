package com.ecommerce.search.integration;

import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProductCardIndex;
import com.ecommerce.search.service.ProductCardReadiness;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Real ES regression tests for paired publication, PIT survival and fencing (ADR-029). */
class ProductCardLifecycleIT extends AbstractSearchIntegrationTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private IndexAdmin indices;
    @Autowired private ProductCardIndex cardsIndex;
    @Autowired private ProductCardProjector projector;
    @Autowired private ProductCardReadiness readiness;
    @Autowired private JobStateRepository jobs;

    @Test
    void pairedCutoverServesTheNewGenerationWhileAnOldCursorKeepsItsSnapshot() throws Exception {
        sku(properties.aliases().productsWrite(), "a", 100);
        sku(properties.aliases().productsWrite(), "b", 200);
        refreshAll();
        var first = rest.getForObject("/api/v2/search/products?size=1&sort=price_asc", SearchDtos.SearchPage.class);
        assertThat(first.totalProducts()).isEqualTo(2);
        String oldCards = cardsIndex.currentGeneration().orElseThrow();
        assertThat(indices.hasOpenSearchContexts(oldCards)).isTrue();
        String oldProducts = indices.resolveAlias(properties.aliases().products()).orElseThrow();
        String nextProducts = indices.createVersionedIndex(IndexTemplates.PRODUCTS, indices.nextVersion(IndexTemplates.PRODUCTS));
        String nextCategories = indices.createVersionedIndex(IndexTemplates.CATEGORIES, indices.nextVersion(IndexTemplates.CATEGORIES));
        var copied = client.reindex(b -> b.source(s -> s.index(oldProducts)).dest(d -> d.index(nextProducts)).refresh(true));
        assertThat(copied.failures()).isEmpty();
        sku(nextProducts, "c", 50);
        indices.refresh(nextProducts);
        try (var prepared = projector.prepareForCutover(nextProducts, () -> {})) {
            // Preparation is private: live reads still see the original consistent generation.
            assertThat(cardsIndex.currentGeneration()).contains(oldCards);
            assertThat(rest.getForObject("/api/v2/search/products?size=1&sort=price_asc",
                    SearchDtos.SearchPage.class).items().getFirst().productId()).isEqualTo("a");
            indices.swapAliasesAtomically(Map.of(nextProducts, List.of("products", "products_write"),
                    nextCategories, List.of("categories", "categories_write"), prepared.index(), List.of("product_cards")));
            prepared.published();
        }
        assertThat(readiness.ready()).isTrue();
        assertThat(cardsIndex.mirroredSourceIndex()).contains(nextProducts);
        assertThat(rest.getForObject("/api/v2/search/products?size=1&sort=price_asc",
                SearchDtos.SearchPage.class).items().getFirst().productId()).isEqualTo("c");
        var second = rest.getForObject("/api/v2/search/products?size=1&sort=price_asc&cursor={cursor}",
                SearchDtos.SearchPage.class, first.nextCursor());
        assertThat(second.totalProducts()).isEqualTo(2);
        assertThat(second.items().getFirst().productId()).isEqualTo("b");
        assertThat(indices.indexExists(oldCards)).isTrue();
    }

    @Test
    void missingProjectionReturnsARetryable503AndNeverGroupsSkus() throws Exception {
        String physical = cardsIndex.currentGeneration().orElseThrow();
        client.indices().updateAliases(b -> b.actions(a -> a.remove(r -> r.index(physical).alias("product_cards"))));
        assertThat(readiness.ready()).isFalse();
        var response = rest.getForEntity("/api/v2/search/products?size=20", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).contains("ELASTICSEARCH_UNAVAILABLE", "projection is not ready");
    }

    @Test
    void anExpiredOwnerCannotRenewOrReleaseTheSuccessorsLease() throws Exception {
        Instant now = Instant.now();
        var old = jobs.acquireLease("cards-fencing-test", "old", now.minusSeconds(20), now.minusSeconds(10)).orElseThrow();
        var successor = jobs.acquireLease("cards-fencing-test", "new", now, now.plusSeconds(60)).orElseThrow();
        assertThat(jobs.renewLease(old, now, now.plusSeconds(60))).isFalse();
        jobs.releaseLease(old);
        assertThat(jobs.holdsLease(successor)).isTrue();
        jobs.releaseLease(successor);
    }

    private void sku(String target, String id, long price) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("sku_id", id + "-sku"); fields.put("sku_code", id); fields.put("product_id", id);
        fields.put("product_slug", id); fields.put("name", "Phone " + id);
        fields.put("product_status", "ACTIVE"); fields.put("sku_status", "ACTIVE");
        fields.put("is_deleted", false); fields.put("is_in_stock", true);
        fields.put("base_price_vnd", 1000); fields.put("selling_price_vnd", price);
        fields.put("indexed_at", Instant.now().toString());
        client.index(b -> b.index(target).id(id + "-sku").document(fields));
    }
}

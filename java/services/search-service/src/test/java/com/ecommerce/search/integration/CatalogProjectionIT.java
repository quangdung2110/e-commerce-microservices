package com.ecommerce.search.integration;

import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.event.CatalogPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard behaviour of the canonical painless scripts, run against a real Elasticsearch.
 *
 * <p>These are the invariants a mock cannot verify: the guard lives inside the script, so
 * only a real update proves that a stale revision is a no-op and that a tombstoned
 * document rejects a late write.
 */
class CatalogProjectionIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00.000Z");

    @Autowired
    private ProjectionWriter writer;

    @Autowired
    private BatchExecutor executor;

    @Test
    void indexesASkuDocumentUnderItsSkuId() throws IOException {
        applyCatalog("sku-1", 1L, 28_891_500L);

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.skuId()).isEqualTo("sku-1");
        assertThat(document.catalogRevision()).isEqualTo(1L);
        assertThat(document.hasPromotion()).isTrue();
    }

    @Test
    void initialisesRatingDefaultsOnTheFirstCatalogWriteOnly() throws IOException {
        applyCatalog("sku-1", 1L, 28_891_500L);
        assertThat(read("sku-1").orElseThrow().productRatingAvg()).isEqualTo(0.0);

        writer.reviewMetricsPatch(properties.aliases().productsWrite(), "prod-1", 4.5, 12L, 7L,
                Timestamps.iso(NOW), "review-1");
        refreshAll();

        applyCatalog("sku-1", 2L, 28_000_000L);

        // SEARCH-BR-009: a later catalog snapshot must not reset the review projection.
        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.productRatingAvg()).isEqualTo(4.5);
        assertThat(document.productRatingCount()).isEqualTo(12L);
        assertThat(document.sellingPriceVnd()).isEqualTo(28_000_000L);
    }

    @Test
    void ignoresACatalogEventOlderThanTheDocument() throws IOException {
        applyCatalog("sku-1", 42L, 28_000_000L);
        applyCatalog("sku-1", 41L, 99_000_000L);

        // docs/search/domain.md §9: revision 41 is dropped entirely, not merged.
        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.catalogRevision()).isEqualTo(42L);
        assertThat(document.sellingPriceVnd()).isEqualTo(28_000_000L);
    }

    @Test
    void replayingTheSameEventIsANoOp() throws IOException {
        applyCatalog("sku-1", 7L, 28_000_000L);
        applyCatalog("sku-1", 7L, 28_000_000L);

        assertThat(read("sku-1").orElseThrow().catalogRevision()).isEqualTo(7L);
    }

    @Test
    void availabilityEventDoesNotCreateADocument() throws IOException {
        // SEARCH-INV-005: only a catalog event may create a SKU document.
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.availabilityPatch(batch.targetIndex(), "sku-unknown", true, 1L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-1"));
        executor.execute("t.inventory.stock.events.v1", batch);
        refreshAll();

        assertThat(read("sku-unknown")).isEmpty();
    }

    @Test
    void availabilityEventArrivingAfterATombstoneDoesNotReviveTheDocument() throws IOException {
        applyCatalog("sku-1", 1L, 28_000_000L);

        EventBatch tombstone = new EventBatch(properties.aliases().productsWrite());
        tombstone.add(writer.catalogTombstone(tombstone.targetIndex(), "sku-1", 2L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "delete-1"));
        executor.execute("t.product.events", tombstone);
        refreshAll();

        EventBatch late = new EventBatch(properties.aliases().productsWrite());
        late.add(writer.availabilityPatch(late.targetIndex(), "sku-1", true, 5L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-late"));
        executor.execute("t.inventory.stock.events.v1", late);
        refreshAll();

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.isDeleted()).isTrue();
        assertThat(document.availabilityRevision()).isNull();
        assertThat(document.inStock()).isFalse();
    }

    @Test
    void availabilityAndCatalogWritesDoNotClobberEachOther() throws IOException {
        applyCatalog("sku-1", 1L, 28_000_000L);
        applyAvailability("sku-1", true, 10L);

        applyCatalog("sku-1", 2L, 27_000_000L);

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.inStock()).isTrue();
        assertThat(document.availabilityRevision()).isEqualTo(10L);
        assertThat(document.sellingPriceVnd()).isEqualTo(27_000_000L);
    }

    @Test
    void aCatalogTombstoneClearsAvailabilityWithoutTouchingItsRevision() throws IOException {
        // SEARCH-BR-009's single exception: the tombstone sets is_in_stock = false as a
        // second line of defence, but leaves availability_revision so Inventory's guard
        // still holds (ADR-011).
        applyCatalog("sku-1", 1L, 28_000_000L);
        applyAvailability("sku-1", true, 10L);

        EventBatch tombstone = new EventBatch(properties.aliases().productsWrite());
        tombstone.add(writer.catalogTombstone(tombstone.targetIndex(), "sku-1", 2L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "delete-1"));
        executor.execute("t.product.events", tombstone);
        refreshAll();

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.inStock()).isFalse();
        assertThat(document.availabilityRevision()).isEqualTo(10L);
    }

    @Test
    void aStaleAvailabilityRevisionDoesNotPutTheSkuBackInStock() throws IOException {
        applyCatalog("sku-1", 1L, 28_000_000L);
        applyAvailability("sku-1", true, 10L);
        applyAvailability("sku-1", false, 11L);
        applyAvailability("sku-1", true, 9L);

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.inStock()).isFalse();
        assertThat(document.availabilityRevision()).isEqualTo(11L);
    }

    @Test
    void aDuplicateAvailabilityRevisionIsANoOp() throws IOException {
        applyCatalog("sku-1", 1L, 28_000_000L);
        applyAvailability("sku-1", true, 10L);
        applyAvailability("sku-1", false, 10L);

        ProductDocument document = read("sku-1").orElseThrow();
        assertThat(document.inStock()).isTrue();
        assertThat(document.availabilityRevision()).isEqualTo(10L);
    }

    private void applyCatalog(String skuId, long revision, long sellingPrice) throws IOException {
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                "prod-1", "cat-1", "iPhone 15", "iphone-15", "A phone",
                Map.of("brand", "Apple"), "ACTIVE", revision, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "SKU-" + skuId, 34_990_000L, sellingPrice, "ACTIVE",
                Map.of("color", "Black"), "media/" + skuId + ".jpg", NOW);

        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                revision, Timestamps.nowIso(), "catalog-" + revision));
        executor.execute("t.product.events", batch);
        refreshAll();
    }

    private void applyAvailability(String skuId, boolean isInStock, long revision) throws IOException {
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.availabilityPatch(batch.targetIndex(), skuId, isInStock, revision,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-" + revision));
        executor.execute("t.inventory.stock.events.v1", batch);
        refreshAll();
    }

    private Optional<ProductDocument> read(String skuId) throws IOException {
        var response = client.get(builder -> builder
                .index(properties.aliases().products()).id(skuId), ProductDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }
}

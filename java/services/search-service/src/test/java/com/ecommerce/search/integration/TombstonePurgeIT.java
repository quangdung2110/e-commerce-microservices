package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.client.InventoryReadinessClient;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.worker.job.TombstonePurgeJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code WF-SEARCH-012} — that a document only disappears once Inventory has agreed to stop
 * publishing availability for it.
 *
 * <p>Deleting first and revoking afterwards would leave a window where Inventory can publish
 * a fact about an identifier Search no longer has, which under ADR-019 is a contract
 * violation the availability consumer refuses to acknowledge — a purge would manufacture the
 * very state the readiness protocol exists to prevent.
 */
class TombstonePurgeIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-08T02:00:00.000Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(60));

    @Autowired
    private ProjectionWriter writer;

    @Autowired
    private BatchExecutor executor;

    @Autowired
    private TombstonePurgeJob purgeJob;

    @MockBean
    private InventoryReadinessClient readiness;

    @BeforeEach
    void allowRevocation() {
        when(readiness.revoke(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.OK);
    }

    @Test
    void purgesADueTombstoneOnlyAfterRevokingItsReadiness() throws Exception {
        index("sku-1", 1L);
        tombstone("sku-1", 2L, LONG_AGO);

        long deleted = purgeJob.run();
        refreshAll();

        assertThat(deleted).isEqualTo(1);
        assertThat(read("sku-1")).isEmpty();
        // The tombstone's own catalog revision, so an older delete cannot revoke a newer
        // registration.
        verify(readiness).revoke("sku-1", 2L);
    }

    @Test
    void leavesTheDocumentInPlaceWhenRevocationConflicts() throws Exception {
        when(readiness.revoke(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.CONFLICT);
        index("sku-1", 1L);
        tombstone("sku-1", 2L, LONG_AGO);

        long deleted = purgeJob.run();
        refreshAll();

        // A conflict means Search and Inventory disagree about this SKU; purging would
        // destroy the evidence and cannot be undone.
        assertThat(deleted).isZero();
        assertThat(read("sku-1")).isPresent();
    }

    @Test
    void leavesTheDocumentInPlaceWhenInventoryIsUnreachable() throws Exception {
        when(readiness.revoke(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.FAILED);
        index("sku-1", 1L);
        tombstone("sku-1", 2L, LONG_AGO);

        assertThat(purgeJob.run()).isZero();
        refreshAll();
        assertThat(read("sku-1")).isPresent();
    }

    @Test
    void doesNotTouchATombstoneStillInsideItsProtectionWindow() throws Exception {
        index("sku-1", 1L);
        tombstone("sku-1", 2L, Instant.now());

        assertThat(purgeJob.run()).isZero();
        refreshAll();

        assertThat(read("sku-1")).isPresent();
        verify(readiness, never()).revoke(anyString(), anyLong());
    }

    @Test
    void neverPurgesALiveDocument() throws Exception {
        index("sku-1", 1L);

        assertThat(purgeJob.run()).isZero();
        refreshAll();

        assertThat(read("sku-1")).isPresent();
        verify(readiness, never()).revoke(anyString(), anyLong());
    }

    // ------------------------------------------------------------------ fixtures

    private void index(String skuId, long revision) throws IOException {
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                "prod-1", "cat-1", "iPhone 15", "iphone-15", "A phone",
                Map.of("brand", "Apple"), "ACTIVE", revision, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "SKU-" + skuId, 34_990_000L, 28_000_000L, "ACTIVE",
                Map.of("color", "Black"), "media/" + skuId + ".jpg", NOW);

        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                revision, Timestamps.nowIso(), "catalog-" + revision));
        executor.execute("t.product.events", batch);
        refreshAll();
    }

    private void tombstone(String skuId, long revision, Instant deletedAt) throws IOException {
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogTombstone(batch.targetIndex(), skuId, revision,
                Timestamps.iso(deletedAt), Timestamps.nowIso(), "delete-" + revision));
        executor.execute("t.product.events", batch);
        refreshAll();
    }

    private Optional<ProductDocument> read(String skuId) throws IOException {
        var response = client.get(builder -> builder
                .index(properties.aliases().products()).id(skuId), ProductDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }
}

package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.worker.ReindexControlCoordinator;
import com.ecommerce.search.worker.client.ExportPages;
import com.ecommerce.search.worker.client.InventoryExportException;
import com.ecommerce.search.worker.client.SnapshotExportClient;
import com.ecommerce.search.worker.handler.AvailabilityEventHandler;
import com.ecommerce.search.worker.handler.BatchExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The availability bootstrap's session lifecycle.
 *
 * <p>An expiring snapshot session is the one failure a reindex cannot resume through, and
 * the one the docs call out by name ({@code WF-SEARCH-007}): the watermark the imported
 * pages belong to is gone, so the recovery is a new session paged from the beginning, not a
 * cursor resume. These are the cases an integration test cannot produce on demand.
 */
class FullReindexJobTest {

    private static final String RUN = "run-1";
    private static final String PRODUCTS = IndexAdmin.physicalName(IndexTemplates.PRODUCTS, 2);
    private static final String CATEGORIES = IndexAdmin.physicalName(IndexTemplates.CATEGORIES, 2);

    private SnapshotExportClient exports;
    private JobStateRepository runs;
    private FullReindexJob job;

    @BeforeEach
    void setUp() throws IOException {
        exports = mock(SnapshotExportClient.class);
        runs = mock(JobStateRepository.class);

        SearchProperties properties = properties(2);
        IndexAdmin indexAdmin = mock(IndexAdmin.class);
        when(indexAdmin.nextVersion(anyString())).thenReturn(2);
        when(indexAdmin.createVersionedIndex(eq(IndexTemplates.PRODUCTS), anyInt())).thenReturn(PRODUCTS);
        when(indexAdmin.createVersionedIndex(eq(IndexTemplates.CATEGORIES), anyInt())).thenReturn(CATEGORIES);
        when(indexAdmin.resolveAlias(anyString())).thenReturn(Optional.empty());

        ThrottledBulkWriter bulkWriter = mock(ThrottledBulkWriter.class);
        ThrottledBulkWriter.Session session = mock(ThrottledBulkWriter.Session.class);
        when(session.failures()).thenReturn(List.of());
        when(bulkWriter.open(anyString(), any())).thenReturn(session);

        ReindexControlCoordinator control = mock(ReindexControlCoordinator.class);
        when(control.supersedePending()).thenReturn(false);

        // Catalog, category and rating exports are not what these cases are about: they
        // return one empty page carrying the replay boundary the run insists on.
        when(exports.categories(any())).thenReturn(new ExportPages.CategoryExportPage(
                List.of(), null, offsets("t.category.events")));
        when(exports.products(any())).thenReturn(new ExportPages.ProductExportPage(
                List.of(), null, offsets("t.product.events")));
        when(exports.ratingAggregates(any())).thenReturn(new ExportPages.RatingExportPage(
                List.of(), null, offsets("t.review.events")));

        job = new FullReindexJob(properties, exports, indexAdmin, mock(ProjectionWriter.class), bulkWriter,
                runs, mock(ReindexCatchUp.class), control, mock(AvailabilityEventHandler.class),
                mock(BatchExecutor.class), new SearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void restartsFromANewSessionAndTheFirstPageWhenTheSnapshotSessionExpires() {
        when(exports.createInventorySnapshotSession())
                .thenReturn(session("snap-1", 100))
                .thenReturn(session("snap-2", 250));
        // The first session dies on its second page, so its first page proves nothing.
        when(exports.skuAvailabilities(eq("snap-1"), isNull()))
                .thenReturn(page("snap-1", 100, "cursor-2"));
        when(exports.skuAvailabilities("snap-1", "cursor-2"))
                .thenThrow(new InventoryExportException(
                        InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED, "snapshot session snap-1 is gone"));
        when(exports.skuAvailabilities(eq("snap-2"), isNull()))
                .thenReturn(page("snap-2", 250, null));
        when(exports.changes(anyLong())).thenReturn(drainedChanges());

        job.run(RUN, "test", "admin");

        // Paged from the beginning of the new session, and never resumed on the old cursor.
        verify(exports).skuAvailabilities("snap-2", null);
        verify(exports, never()).skuAvailabilities("snap-2", "cursor-2");
        // The change feed picks up at the new watermark, not the abandoned one. It is read
        // more than once on purpose: once to finish the build, once after catch-up to pick
        // up what readiness registration made Inventory republish.
        verify(exports, atLeastOnce()).changes(250L);
        verify(exports, never()).changes(100L);
        assertThat(finalStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void bootstrapsAgainFromANewSessionWhenTheReplayWindowExpires() {
        when(exports.createInventorySnapshotSession())
                .thenReturn(session("snap-1", 100))
                .thenReturn(session("snap-2", 250));
        when(exports.skuAvailabilities(anyString(), isNull()))
                .thenReturn(page("snap-1", 100, null))
                .thenReturn(page("snap-2", 250, null));
        when(exports.changes(100L)).thenThrow(new InventoryExportException(
                InventoryExportException.Reason.REPLAY_WINDOW_EXPIRED, "change feed after sequence 100 is gone"));
        when(exports.changes(250L)).thenReturn(drainedChanges());

        job.run(RUN, "test", "admin");

        verify(exports, times(2)).createInventorySnapshotSession();
        assertThat(finalStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void failsTheRunRatherThanLoopWhenEverySessionExpires() {
        when(exports.createInventorySnapshotSession())
                .thenReturn(session("snap-1", 100), session("snap-2", 250), session("snap-3", 400));
        when(exports.skuAvailabilities(anyString(), any())).thenThrow(new InventoryExportException(
                InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED, "gone"));

        job.run(RUN, "test", "admin");

        // Bounded by availability_snapshot_max_sessions, and no cutover.
        verify(exports, times(2)).createInventorySnapshotSession();
        assertThat(finalStatus()).isEqualTo("FAILED");
        assertThat(finalFailureMessage()).contains("snapshot sessions");
    }

    // ------------------------------------------------------------------ fixtures

    private String finalStatus() {
        return lastRun().status();
    }

    private String finalFailureMessage() {
        return String.valueOf(lastRun().failureMessage());
    }

    private ReindexRunDocument lastRun() {
        ArgumentCaptor<ReindexRunDocument> saved = ArgumentCaptor.forClass(ReindexRunDocument.class);
        try {
            verify(runs, atLeastOnce()).saveRun(saved.capture());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return saved.getAllValues().get(saved.getAllValues().size() - 1);
    }

    private static ExportPages.SnapshotSession session(String id, long watermark) {
        return new ExportPages.SnapshotSession(id, watermark, Instant.now(), Instant.now().plus(Duration.ofHours(24)));
    }

    private static ExportPages.SkuAvailabilitySnapshotPage page(String id, long watermark, String nextCursor) {
        return new ExportPages.SkuAvailabilitySnapshotPage(id, watermark, List.of(), nextCursor);
    }

    private static ExportPages.ProjectionChangePage drainedChanges() {
        return new ExportPages.ProjectionChangePage(List.of(), 0L, 0L, null);
    }

    private static List<ExportPages.ReplayOffset> offsets(String topic) {
        return List.of(new ExportPages.ReplayOffset(topic, 0, 0L));
    }

    private static SearchProperties properties(int maxSessions) {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write",
                "search_suggestions", "search_jobs"));
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", "http://inventory-service:8080", "http://review-service:8080",
                true, Duration.ofSeconds(30), maxSessions));
        when(properties.reindex()).thenReturn(new SearchProperties.Reindex(
                Duration.ofHours(6), 500, 1, Duration.ofMillis(10), Duration.ofSeconds(5), 0.02));
        when(properties.topics()).thenReturn(new SearchProperties.Topics(
                "t.product.events", "t.category.events", "t.catalog.control.events",
                "t.inventory.stock.events.v1", "t.review.events"));
        return properties;
    }
}

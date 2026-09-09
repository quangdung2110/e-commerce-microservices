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
import com.ecommerce.search.worker.client.RatingExportSessionException;
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
import java.util.Map;
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
 * The export session lifecycles of a full reindex.
 *
 * <p>An expiring session is the one failure a reindex cannot resume through, and the one the
 * docs call out by name for both sources: the boundary the imported pages belong to is gone,
 * so the recovery is a new session paged from the beginning, not a cursor resume. Inventory
 * loses a watermark ({@code WF-SEARCH-007}) and Review loses its replay offsets
 * ({@code WF-SEARCH-009} step 3), but the shape is the same. These are the cases an
 * integration test cannot produce on demand.
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
        when(exports.createRatingExportSession()).thenReturn(ratingSession("rev-1", 0L));
        when(exports.ratingAggregates(anyString(), any())).thenReturn(ratingPage("rev-1", null));

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

    @Test
    void restartsRatingFromANewSessionAndTheFirstPageWhenTheExportSessionExpires() {
        givenAvailabilityCompletes();
        when(exports.createRatingExportSession())
                .thenReturn(ratingSession("rev-1", 100L))
                .thenReturn(ratingSession("rev-2", 900L));
        // The first session dies on its second page, so its offsets can never be seeked to.
        when(exports.ratingAggregates(eq("rev-1"), isNull())).thenReturn(ratingPage("rev-1", "p-500"));
        when(exports.ratingAggregates("rev-1", "p-500"))
                .thenThrow(new RatingExportSessionException("rating export session rev-1 is gone: HTTP 410"));
        when(exports.ratingAggregates(eq("rev-2"), isNull())).thenReturn(ratingPage("rev-2", null));

        job.run(RUN, "test", "admin");

        // Paged from the beginning of the new session, never resumed on the old keyset.
        verify(exports).ratingAggregates("rev-2", null);
        verify(exports, never()).ratingAggregates("rev-2", "p-500");
        // And catch-up seeks to the surviving session's offsets. Keeping the abandoned ones
        // would start the replay after this session's snapshot and lose everything between.
        assertThat(reviewReplayOffset()).isEqualTo(900L);
        assertThat(finalStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void failsTheRunRatherThanLoopWhenEveryRatingExportSessionExpires() {
        givenAvailabilityCompletes();
        when(exports.createRatingExportSession())
                .thenReturn(ratingSession("rev-1", 100L), ratingSession("rev-2", 900L),
                        ratingSession("rev-3", 1500L));
        when(exports.ratingAggregates(anyString(), any()))
                .thenThrow(new RatingExportSessionException("gone"));

        job.run(RUN, "test", "admin");

        // Bounded by rating_export_max_sessions, and no cutover.
        verify(exports, times(2)).createRatingExportSession();
        assertThat(finalStatus()).isEqualTo("FAILED");
        assertThat(finalFailureMessage()).contains("export sessions");
    }

    @Test
    void failsTheRunWhenTheRatingExportSessionCarriesNoOffsets() {
        givenAvailabilityCompletes();
        when(exports.createRatingExportSession()).thenReturn(
                new ExportPages.RatingExportSession("rev-1", Instant.now().plus(Duration.ofHours(1)), List.of()));

        job.run(RUN, "test", "admin");

        // No boundary means catch-up has no start; the run must not reach cutover.
        verify(exports, never()).ratingAggregates(anyString(), any());
        assertThat(finalStatus()).isEqualTo("FAILED");
        assertThat(finalFailureMessage()).contains("replay_from_offsets");
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

    /** Rating is the last build phase, so everything before it has to land first. */
    private void givenAvailabilityCompletes() {
        when(exports.createInventorySnapshotSession()).thenReturn(session("snap-1", 100));
        when(exports.skuAvailabilities(anyString(), any())).thenReturn(page("snap-1", 100, null));
        when(exports.changes(anyLong())).thenReturn(drainedChanges());
    }

    private static ExportPages.RatingExportSession ratingSession(String id, long offset) {
        return new ExportPages.RatingExportSession(id, Instant.now().plus(Duration.ofHours(1)),
                List.of(new ExportPages.ReplayOffset("t.review.events", 0, offset)));
    }

    private static ExportPages.RatingExportPage ratingPage(String id, String nextAfterProductId) {
        return new ExportPages.RatingExportPage(id, List.of(), nextAfterProductId,
                List.of(new ExportPages.ReplayOffset("t.review.events", 0, 0L)));
    }

    /** The review boundary as it was persisted for catch-up to seek to. */
    @SuppressWarnings("unchecked")
    private long reviewReplayOffset() {
        Map<String, Object> bySource = (Map<String, Object>) lastRun().replayFromOffsets().get(0);
        List<Map<String, Object>> review = (List<Map<String, Object>>) bySource.get("review");
        assertThat(review).hasSize(1);
        return ((Number) review.get(0).get("offset")).longValue();
    }

    private static SearchProperties properties(int maxSessions) {
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write",
                "search_suggestions", "search_jobs"));
        when(properties.export()).thenReturn(new SearchProperties.Export(
                "http://product-service:8080", "http://inventory-service:8080", "http://review-service:8080",
                true, Duration.ofSeconds(30), maxSessions, maxSessions));
        when(properties.reindex()).thenReturn(new SearchProperties.Reindex(
                Duration.ofHours(6), 500, 1, Duration.ofMillis(10), Duration.ofSeconds(5), 0.02));
        when(properties.topics()).thenReturn(new SearchProperties.Topics(
                "t.product.events", "t.category.events", "t.catalog.control.events",
                "t.inventory.stock.events.v1", "t.review.events"));
        return properties;
    }
}

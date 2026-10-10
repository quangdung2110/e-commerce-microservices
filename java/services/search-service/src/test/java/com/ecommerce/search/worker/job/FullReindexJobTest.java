package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
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
import java.util.LinkedHashMap;
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
 * The export session lifecycles of a full reindex, and the run's own life cycle: stage
 * ordering, resume, cancellation, lease loss and the atomic cutover.
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
    private static final String OLD_PRODUCTS = IndexAdmin.physicalName(IndexTemplates.PRODUCTS, 1);
    private static final String OLD_CATEGORIES = IndexAdmin.physicalName(IndexTemplates.CATEGORIES, 1);

    private SnapshotExportClient exports;
    private JobStateRepository runs;
    private ReindexControlCoordinator control;
    private IndexAdmin indexAdmin;
    private ReindexCatchUp catchUp;
    private ReindexVerifier verifier;
    private FullReindexJob job;

    @BeforeEach
    void setUp() throws IOException {
        exports = mock(SnapshotExportClient.class);
        runs = mock(JobStateRepository.class);
        when(runs.holdsLease(any())).thenReturn(true);
        when(runs.renewLease(any(), any(), any())).thenReturn(true);
        when(runs.isCancelRequested(anyString())).thenReturn(false);

        SearchProperties properties = properties(2);
        indexAdmin = mock(IndexAdmin.class);
        when(indexAdmin.nextVersion(anyString())).thenReturn(2);
        when(indexAdmin.createVersionedIndex(eq(IndexTemplates.PRODUCTS), anyInt())).thenReturn(PRODUCTS);
        when(indexAdmin.createVersionedIndex(eq(IndexTemplates.CATEGORIES), anyInt())).thenReturn(CATEGORIES);
        when(indexAdmin.resolveAlias(anyString())).thenReturn(Optional.empty());

        ThrottledBulkWriter bulkWriter = mock(ThrottledBulkWriter.class);
        ThrottledBulkWriter.Session session = mock(ThrottledBulkWriter.Session.class);
        when(session.failures()).thenReturn(List.of());
        when(bulkWriter.open(anyString(), any())).thenReturn(session);

        control = mock(ReindexControlCoordinator.class);
        when(control.supersedePending(any())).thenReturn(false);
        catchUp = mock(ReindexCatchUp.class);
        verifier = mock(ReindexVerifier.class);

        // Catalog, category and rating exports are not what these cases are about: they
        // return one empty page carrying the replay boundary the run insists on.
        when(exports.categories(any())).thenReturn(new ExportPages.CategoryExportPage(
                List.of(), null, offsets("t.category.events")));
        when(exports.products(any())).thenReturn(new ExportPages.ProductExportPage(
                List.of(), null, offsets("t.product.events")));
        when(exports.createRatingExportSession()).thenReturn(ratingSession("rev-1", 0L));
        when(exports.ratingAggregates(anyString(), any())).thenReturn(ratingPage("rev-1", null));

        job = new FullReindexJob(properties, exports, indexAdmin, mock(ProjectionWriter.class), bulkWriter,
                runs, catchUp, control, mock(AvailabilityEventHandler.class),
                mock(BatchExecutor.class), new SearchMetrics(new SimpleMeterRegistry()), verifier);
    }

    private void run() {
        job.run(RUN, "test", "admin", lease(), null);
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

        run();

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

        run();

        verify(exports, times(2)).createInventorySnapshotSession();
        assertThat(finalStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void failsTheRunRatherThanLoopWhenEverySessionExpires() {
        when(exports.createInventorySnapshotSession())
                .thenReturn(session("snap-1", 100), session("snap-2", 250), session("snap-3", 400));
        when(exports.skuAvailabilities(anyString(), any())).thenThrow(new InventoryExportException(
                InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED, "gone"));

        run();

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

        run();

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

        run();

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

        run();

        // No boundary means catch-up has no start; the run must not reach cutover.
        verify(exports, never()).ratingAggregates(anyString(), any());
        assertThat(finalStatus()).isEqualTo("FAILED");
        assertThat(finalFailureMessage()).contains("replay_from_offsets");
    }

    @Test
    void endsAsSupersededNotFailedWhenAControlEventArrivesDuringTheBuild() throws IOException {
        givenAvailabilityCompletes();
        // ADR-017: a catalog-reindex-requested event during Build/Catch-up supersedes the run.
        when(control.supersedePending(any())).thenReturn(true);

        run();

        // The aliases stay put and the terminal state is SUPERSEDED (OpenAPI ReindexRun.status).
        assertThat(finalStatus()).isEqualTo("SUPERSEDED");
        assertThat(lastRun().failureCode()).isEqualTo("RUN_SUPERSEDED");
        verify(indexAdmin).deleteIndex(PRODUCTS);
        verify(indexAdmin, never()).swapAliasesAtomically(any());
    }

    @Test
    void endsAsCancelledAndDeletesWhatItBuiltWhenAnAdministratorCancels() throws IOException {
        givenAvailabilityCompletes();
        when(runs.isCancelRequested(RUN)).thenReturn(true);

        run();

        assertThat(finalStatus()).isEqualTo("CANCELLED");
        assertThat(lastRun().failureCode()).isEqualTo("RUN_CANCELLED");
        verify(indexAdmin).deleteIndex(PRODUCTS);
        verify(indexAdmin).deleteIndex(CATEGORIES);
        verify(runs).clearCancel(RUN);
        verify(indexAdmin, never()).swapAliasesAtomically(any());
    }

    @Test
    void movesEveryAliasInOneRequestAndWritesDownTheIndicesItReplaced() throws IOException {
        givenAvailabilityCompletes();
        when(indexAdmin.resolveAlias("products")).thenReturn(Optional.of(OLD_PRODUCTS));
        when(indexAdmin.resolveAlias("categories")).thenReturn(Optional.of(OLD_CATEGORIES));

        run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> targets = ArgumentCaptor.forClass(Map.class);
        verify(indexAdmin, times(1)).swapAliasesAtomically(targets.capture());
        assertThat(targets.getValue()).containsOnlyKeys(PRODUCTS, CATEGORIES);
        assertThat(targets.getValue().get(PRODUCTS)).containsExactlyInAnyOrder("products", "products_write");
        assertThat(targets.getValue().get(CATEGORIES)).containsExactlyInAnyOrder("categories", "categories_write");
        // The way back is recorded, and the cleanup clock for the replaced indices starts.
        verify(runs).markIndexRetired(eq(OLD_PRODUCTS), eq(RUN), any());
        verify(runs).markIndexRetired(eq(OLD_CATEGORIES), eq(RUN), any());
        assertThat(checkpointOf(lastRun()).get("previous_products_index")).isEqualTo(OLD_PRODUCTS);
        assertThat(checkpointOf(lastRun()).get("previous_categories_index")).isEqualTo(OLD_CATEGORIES);
        verify(control).satisfiedBy(any());
        verify(catchUp).replayTail(eq(RUN), eq(PRODUCTS), eq(CATEGORIES), any(), any());
    }

    @Test
    void refusesToCutOverAnIndexThatFailsVerificationAndDeletesIt() throws Exception {
        givenAvailabilityCompletes();
        org.mockito.Mockito.doThrow(new IllegalStateException("sample diff found 3 of 200 SKUs wrong"))
                .when(verifier).verify(any(), anyString(), any());

        run();

        assertThat(finalStatus()).isEqualTo("FAILED");
        assertThat(finalFailureMessage()).contains("sample diff");
        verify(indexAdmin).deleteIndex(PRODUCTS);
        verify(indexAdmin, never()).swapAliasesAtomically(any());
    }

    @Test
    void stopsWithoutSwappingWhenTheLeaseIsNoLongerHeldAtCutover() throws IOException {
        givenAvailabilityCompletes();
        when(runs.holdsLease(any())).thenReturn(false);

        run();

        // Another process owns the run now; this one must not move the aliases or overwrite its state.
        verify(indexAdmin, never()).swapAliasesAtomically(any());
        assertThat(finalStatus()).isEqualTo("CUTOVER");
    }

    @Test
    void resumesAtTheSavedStageWithoutRepeatingTheBuild() throws IOException {
        givenAvailabilityCompletes();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("snapshot_id", "snap-1");
        inventory.put("export_watermark", 100L);
        inventory.put("handoff_proven", true);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("stage", "CATCH_UP");
        checkpoint.put("products_index", PRODUCTS);
        checkpoint.put("categories_index", CATEGORIES);
        checkpoint.put("resume_attempts", 1);
        checkpoint.put("inventory_availability", inventory);
        ReindexRunDocument saved = new ReindexRunDocument(ReindexRunDocument.DOC_KIND, RUN, "CATCHING_UP",
                "CATCHING_UP", "test", "admin", Instant.now().minusSeconds(600), null, Instant.now().minusSeconds(300),
                null, null, PRODUCTS, Map.of("products_exported", 42L), List.of(Map.of(
                        "category", List.of(Map.of("topic", "t.category.events", "partition", 0, "offset", 7)),
                        "product", List.of(Map.of("topic", "t.product.events", "partition", 0, "offset", 9)),
                        "review", List.of(Map.of("topic", "t.review.events", "partition", 0, "offset", 3)))),
                checkpoint);

        job.run(RUN, "test", "admin", lease(), saved);

        // Nothing before the saved stage runs again, and no new index is created.
        verify(exports, never()).categories(any());
        verify(exports, never()).products(any());
        verify(exports, never()).createInventorySnapshotSession();
        verify(indexAdmin, never()).createVersionedIndex(anyString(), anyInt());
        ArgumentCaptor<ReindexProgressState> progress = ArgumentCaptor.forClass(ReindexProgressState.class);
        verify(catchUp).replay(eq(RUN), eq(PRODUCTS), eq(CATEGORIES), progress.capture(), any(), any());
        assertThat(progress.getValue().allOffsets()).hasSize(3);
        assertThat(progress.getValue().resumeAttempts()).isEqualTo(1);
        assertThat(finalStatus()).isEqualTo("COMPLETED");
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

    private static Map<String, Object> checkpointOf(ReindexRunDocument run) {
        return run.checkpointOrEmpty();
    }

    private static LeaseLockDocument lease() {
        return new LeaseLockDocument(LeaseLockDocument.DOC_KIND, LeaseLockDocument.REINDEX_LOCK, RUN,
                Instant.now().plusSeconds(120), 1L, Instant.now());
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
                "http://product-service:8081", "http://inventory-service:8080", "http://review-service:8080",
                "review-token", true, Duration.ofSeconds(30), maxSessions, maxSessions));
        when(properties.reindex()).thenReturn(new SearchProperties.Reindex(
                Duration.ofHours(6), 500, 1, Duration.ofMillis(10), Duration.ofSeconds(5), 0.02));
        when(properties.topics()).thenReturn(new SearchProperties.Topics(
                "t.product.events", "t.category.events", "t.catalog.control.events",
                "t.inventory.stock.events.v1", "t.review.events"));
        when(properties.lifecycle()).thenReturn(new SearchProperties.Lifecycle(
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, Duration.ofMinutes(2),
                true, true, 1.5, Duration.ofHours(24), "0 15 * * * *", true));
        return properties;
    }
}

package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.worker.job.FullReindexJob;
import com.ecommerce.search.worker.job.ReindexRecovery;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin operations around a reindex run: trigger, cancel and rollback. */
class ReindexRunServiceTest {

    private static final String RUN = "run-1";

    private JobStateRepository runs;
    private FullReindexJob job;
    private ReindexPreconditions preconditions;
    private ReindexRecovery recovery;
    private IndexAdmin indexAdmin;
    private ReindexRunService service;

    @BeforeEach
    void setUp() throws IOException {
        runs = mock(JobStateRepository.class);
        job = mock(FullReindexJob.class);
        preconditions = mock(ReindexPreconditions.class);
        recovery = mock(ReindexRecovery.class);
        indexAdmin = mock(IndexAdmin.class);
        ProductCardProjector cards = mock(ProductCardProjector.class);
        var prepared = mock(ProductCardProjector.PreparedGeneration.class);
        when(prepared.index()).thenReturn("product_cards_v3");
        when(cards.prepareForCutover(anyString(), any())).thenReturn(prepared);
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write", "search_suggestions", "search_jobs"));
        when(properties.lifecycle()).thenReturn(new SearchProperties.Lifecycle(
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, Duration.ofMinutes(2),
                true, true, 1.5, Duration.ofHours(24), "0 15 * * * *", true));
        when(recovery.settleAbandoned()).thenReturn(ReindexRecovery.Outcome.NOTHING_ACTIVE);
        service = new ReindexRunService(runs, job, preconditions, recovery, indexAdmin, properties,
                new SearchMetrics(new SimpleMeterRegistry()), cards);
    }

    // ------------------------------------------------------------------ trigger

    @Test
    void aTriggerStartsTheJobWithTheLeaseItAcquired() throws IOException {
        LeaseLockDocument lease = lease(Instant.now().plusSeconds(120));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), anyString(), any(), any()))
                .thenReturn(Optional.of(lease));
        when(runs.findActiveRun()).thenReturn(Optional.empty());
        when(runs.findRun(anyString())).thenAnswer(call -> Optional.of(startedRun(call.getArgument(0))));

        var started = service.trigger("mapping change", "admin-1");

        assertThat(started.status()).isEqualTo("STARTED");
        assertThat(started.actor()).isEqualTo("admin-1");
        verify(preconditions).check();
        verify(job).run(anyString(), eq("mapping change"), eq("admin-1"), eq(lease), eq(null));
    }

    @Test
    void aTriggerIsRefusedWhileAnotherRunHoldsTheLease() throws IOException {
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trigger("again", "admin-1"))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_ALREADY_RUNNING));
        verify(job, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void aTriggerIsRefusedAndTheLeaseGivenBackWhenAnUnfinishedRunSlippedIn() throws IOException {
        LeaseLockDocument lease = lease(Instant.now().plusSeconds(120));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), anyString(), any(), any()))
                .thenReturn(Optional.of(lease));
        when(runs.findActiveRun()).thenReturn(Optional.of(startedRun("other")));

        assertThatThrownBy(() -> service.trigger("again", "admin-1"))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_ALREADY_RUNNING));
        verify(runs).releaseLease(lease);
    }

    @Test
    void aLiveRunOrOneJustResumedBlocksTheTrigger() throws IOException {
        when(recovery.settleAbandoned()).thenReturn(ReindexRecovery.Outcome.RESUMED);

        assertThatThrownBy(() -> service.trigger("again", "admin-1"))
                .isInstanceOfSatisfying(SearchException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_ALREADY_RUNNING);
                    assertThat(e.getMessage()).contains("resumed");
                });
        verify(preconditions, never()).check();
    }

    @Test
    void aFailedPreconditionStopsTheTriggerBeforeAnythingIsCreated() throws IOException {
        org.mockito.Mockito.doThrow(new SearchException(ErrorCode.REINDEX_PRECONDITION_FAILED, "cluster red"))
                .when(preconditions).check();

        assertThatThrownBy(() -> service.trigger("again", "admin-1"))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_PRECONDITION_FAILED));
        verify(runs, never()).acquireLease(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void aFinishedRunCannotBeCancelled() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("COMPLETED", Map.of())));

        assertThatThrownBy(() -> service.cancel(RUN, "admin-1"))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_RUN_NOT_CANCELLABLE));
        verify(runs, never()).requestCancel(any(), any(), any());
    }

    @Test
    void aRunSwitchingTheAliasesCannotBeCancelled() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("CUTOVER", Map.of())));

        assertThatThrownBy(() -> service.cancel(RUN, "admin-1"))
                .isInstanceOfSatisfying(SearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_RUN_NOT_CANCELLABLE));
    }

    @Test
    void cancellingARunWithALiveOwnerOnlyRecordsTheRequest() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("BUILDING", Map.of())));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK))
                .thenReturn(Optional.of(lease(Instant.now().plusSeconds(60))));

        var view = service.cancel(RUN, "admin-1");

        verify(runs).requestCancel(eq(RUN), eq("admin-1"), any());
        verify(recovery, never()).cancelAbandoned(any());
        assertThat(view.status()).isEqualTo("BUILDING");
    }

    @Test
    void cancellingARunWithNoOwnerLeftFinishesItAtOnce() throws IOException {
        ReindexRunDocument building = run("BUILDING", Map.of());
        when(runs.findRun(RUN)).thenReturn(Optional.of(building));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK)).thenReturn(Optional.empty());

        service.cancel(RUN, "admin-1");

        verify(runs).requestCancel(eq(RUN), eq("admin-1"), any());
        verify(recovery).cancelAbandoned(building);
    }

    // ------------------------------------------------------------------ rollback

    @Test
    void onlyACompletedRunCanBeRolledBack() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("FAILED", Map.of())));

        assertUnavailable("only a COMPLETED run");
    }

    @Test
    void aFirstBuildHasNothingToReturnTo() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("COMPLETED", cutoverCheckpoint(null, null))));

        assertUnavailable("first build");
    }

    @Test
    void anotherRunInProgressBlocksTheRollbackAndGivesTheLeaseBack() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("COMPLETED", cutoverCheckpoint("products_v1", "categories_v1"))));
        LeaseLockDocument lease = lease(Instant.now().plusSeconds(60));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq("rollback:" + RUN), any(), any()))
                .thenReturn(Optional.of(lease));
        when(runs.findActiveRun()).thenReturn(Optional.of(startedRun("other")));

        assertUnavailable("in progress");
        verify(runs).releaseLease(lease);
    }

    @Test
    void aNewerCutoverMakesTheRollbackImpossible() throws IOException {
        givenRollbackCandidate();
        when(indexAdmin.resolveAlias("products")).thenReturn(Optional.of("products_v9"));
        when(indexAdmin.resolveAlias("categories")).thenReturn(Optional.of("categories_v2"));

        assertUnavailable("latest cutover");
        verify(indexAdmin, never()).swapAliasesAtomically(any());
    }

    @Test
    void aPreviousIndexAlreadyCleanedUpMakesTheRollbackImpossible() throws IOException {
        givenRollbackCandidate();
        when(indexAdmin.resolveAlias("products")).thenReturn(Optional.of("products_v2"));
        when(indexAdmin.resolveAlias("categories")).thenReturn(Optional.of("categories_v2"));
        when(indexAdmin.indexExists("products_v1")).thenReturn(false);

        assertUnavailable("removed after the retention window");
        verify(indexAdmin, never()).swapAliasesAtomically(any());
    }

    @Test
    void aRollbackMovesAllFourAliasesBackInOneRequestAndMarksTheRun() throws IOException {
        givenRollbackCandidate();
        when(indexAdmin.resolveAlias("products")).thenReturn(Optional.of("products_v2"));
        when(indexAdmin.resolveAlias("categories")).thenReturn(Optional.of("categories_v2"));
        when(indexAdmin.indexExists(anyString())).thenReturn(true);

        service.rollback(RUN, "admin-1");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, List<String>>> targets = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(indexAdmin).swapAliasesAtomically(targets.capture());
        assertThat(targets.getValue()).containsOnlyKeys("products_v1", "categories_v1", "product_cards_v3");
        assertThat(targets.getValue().get("products_v1")).containsExactlyInAnyOrder("products", "products_write");
        org.mockito.ArgumentCaptor<ReindexRunDocument> saved = org.mockito.ArgumentCaptor.forClass(ReindexRunDocument.class);
        verify(runs).saveRun(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("ROLLED_BACK");
        assertThat(saved.getValue().checkpointOrEmpty()).containsEntry("rolled_back_by", "admin-1");
        verify(runs).markIndexRetired(eq("products_v2"), eq(RUN), any());
        verify(runs).clearIndexRetired("products_v1");
    }

    // ------------------------------------------------------------------ fixtures

    private void givenRollbackCandidate() throws IOException {
        when(runs.findRun(RUN)).thenReturn(Optional.of(run("COMPLETED", cutoverCheckpoint("products_v1", "categories_v1"))));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq("rollback:" + RUN), any(), any()))
                .thenReturn(Optional.of(lease(Instant.now().plusSeconds(60))));
        when(runs.findActiveRun()).thenReturn(Optional.empty());
    }

    private void assertUnavailable(String messagePart) {
        assertThatThrownBy(() -> service.rollback(RUN, "admin-1"))
                .isInstanceOfSatisfying(SearchException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REINDEX_ROLLBACK_UNAVAILABLE);
                    assertThat(e.getMessage()).contains(messagePart);
                });
    }

    private static Map<String, Object> cutoverCheckpoint(String previousProducts, String previousCategories) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("stage", "DONE");
        checkpoint.put("products_index", "products_v2");
        checkpoint.put("categories_index", "categories_v2");
        checkpoint.put("previous_products_index", previousProducts);
        checkpoint.put("previous_categories_index", previousCategories);
        checkpoint.put("cutover_at", Instant.now().toString());
        return checkpoint;
    }

    private static ReindexRunDocument run(String status, Map<String, Object> checkpoint) {
        return new ReindexRunDocument(ReindexRunDocument.DOC_KIND, RUN, status, null, "why", "admin",
                Instant.now().minusSeconds(900), null, Instant.now(), null, null, "products_v2",
                Map.of(), List.of(), checkpoint);
    }

    private static ReindexRunDocument startedRun(String runId) {
        return new ReindexRunDocument(ReindexRunDocument.DOC_KIND, runId, "STARTED", null, "mapping change",
                "admin-1", Instant.now(), null, Instant.now(), null, null, null, Map.of(), List.of(), Map.of());
    }

    private static LeaseLockDocument lease(Instant expiresAt) {
        return new LeaseLockDocument(LeaseLockDocument.DOC_KIND, LeaseLockDocument.REINDEX_LOCK, RUN, expiresAt,
                1L, Instant.now());
    }
}

package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who deals with a reindex run whose owner stopped renewing its lease, and how (ADR-022). */
class ReindexRecoveryTest {

    private static final String RUN = "run-1";

    private JobStateRepository runs;
    private FullReindexJob job;
    private ReindexRecovery recovery;

    @BeforeEach
    void setUp() {
        runs = mock(JobStateRepository.class);
        job = mock(FullReindexJob.class);
        SearchProperties properties = mock(SearchProperties.class);
        when(properties.lifecycle()).thenReturn(new SearchProperties.Lifecycle(
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, Duration.ofMinutes(2),
                true, true, 1.5, Duration.ofHours(24), "0 15 * * * *", true));
        recovery = new ReindexRecovery(runs, job, properties, new SearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void doesNothingWithoutAnUnfinishedRun() throws IOException {
        when(runs.findActiveRun()).thenReturn(Optional.empty());

        assertThat(recovery.settleAbandoned()).isEqualTo(ReindexRecovery.Outcome.NOTHING_ACTIVE);
        verify(job, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void leavesARunAloneWhileItsOwnerKeepsRenewingTheLease() throws IOException {
        when(runs.findActiveRun()).thenReturn(Optional.of(run(0)));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK)).thenReturn(Optional.of(lease(Instant.now().plusSeconds(60))));

        assertThat(recovery.settleAbandoned()).isEqualTo(ReindexRecovery.Outcome.ALIVE);
        verify(runs, never()).acquireLease(any(), any(), any(), any());
        verify(job, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void stepsAsideWhenAnotherReplicaWinsTheExpiredLeaseFirst() throws IOException {
        when(runs.findActiveRun()).thenReturn(Optional.of(run(0)));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK)).thenReturn(Optional.of(lease(Instant.now().minusSeconds(60))));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq(RUN), any(), any())).thenReturn(Optional.empty());

        assertThat(recovery.settleAbandoned()).isEqualTo(ReindexRecovery.Outcome.ALIVE);
        verify(job, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void resumesAnAbandonedRunAndCountsTheAttempt() throws IOException {
        when(runs.findActiveRun()).thenReturn(Optional.of(run(1)));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK)).thenReturn(Optional.empty());
        LeaseLockDocument acquired = lease(Instant.now().plusSeconds(120));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq(RUN), any(), any()))
                .thenReturn(Optional.of(acquired));

        assertThat(recovery.settleAbandoned()).isEqualTo(ReindexRecovery.Outcome.RESUMED);

        ArgumentCaptor<ReindexRunDocument> resumed = ArgumentCaptor.forClass(ReindexRunDocument.class);
        verify(job).run(eq(RUN), eq("why"), eq("admin"), eq(acquired), resumed.capture());
        assertThat(resumed.getValue().checkpointOrEmpty()).containsEntry("resume_attempts", 2)
                .containsEntry("stage", "BUILD_CATALOG");
        // The counted attempt is saved before the job starts, so a crash during it still counts.
        verify(runs).saveRun(resumed.getValue());
    }

    @Test
    void failsARunAsAbandonedOnceItUsedAllItsResumes() throws IOException {
        when(runs.findActiveRun()).thenReturn(Optional.of(run(3)));
        when(runs.readLease(LeaseLockDocument.REINDEX_LOCK)).thenReturn(Optional.empty());
        LeaseLockDocument acquired = lease(Instant.now().plusSeconds(120));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq(RUN), any(), any()))
                .thenReturn(Optional.of(acquired));

        assertThat(recovery.settleAbandoned()).isEqualTo(ReindexRecovery.Outcome.ABANDONED);

        ArgumentCaptor<ReindexRunDocument> saved = ArgumentCaptor.forClass(ReindexRunDocument.class);
        verify(runs).saveRun(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("FAILED");
        assertThat(saved.getValue().failureCode()).isEqualTo("RUN_ABANDONED");
        assertThat(saved.getValue().finishedAt()).isNotNull();
        verify(job).discard(any());
        verify(runs).releaseLease(acquired);
        verify(job, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void anAbandonedRunAnAdministratorCancelsEndsCancelledAndFreesTheSlot() throws IOException {
        LeaseLockDocument acquired = lease(Instant.now().plusSeconds(120));
        when(runs.acquireLease(eq(LeaseLockDocument.REINDEX_LOCK), eq(RUN), any(), any()))
                .thenReturn(Optional.of(acquired));

        recovery.cancelAbandoned(run(0));

        ArgumentCaptor<ReindexRunDocument> saved = ArgumentCaptor.forClass(ReindexRunDocument.class);
        verify(runs).saveRun(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("CANCELLED");
        assertThat(saved.getValue().failureCode()).isEqualTo("RUN_CANCELLED");
        verify(job).discard(any());
        verify(runs).clearCancel(RUN);
        verify(runs).releaseLease(acquired);
    }

    private static ReindexRunDocument run(int resumeAttempts) {
        return new ReindexRunDocument(ReindexRunDocument.DOC_KIND, RUN, "BUILDING", "BUILDING", "why", "admin",
                Instant.now().minusSeconds(900), null, Instant.now().minusSeconds(600), null, null, "products_v2",
                Map.of("products_exported", 5L), List.of(Map.of()),
                Map.of("stage", "BUILD_CATALOG", "products_index", "products_v2",
                        "categories_index", "categories_v2", "resume_attempts", resumeAttempts));
    }

    private static LeaseLockDocument lease(Instant expiresAt) {
        return new LeaseLockDocument(LeaseLockDocument.DOC_KIND, LeaseLockDocument.REINDEX_LOCK, RUN, expiresAt,
                4L, Instant.now());
    }
}

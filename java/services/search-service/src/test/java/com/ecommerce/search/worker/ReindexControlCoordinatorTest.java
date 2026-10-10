package com.ecommerce.search.worker;

import com.ecommerce.search.domain.document.ReindexRequestDocument;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.repository.JobStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coalescing and superseding of category-tree change requests (ADR-017, ADR-022). */
class ReindexControlCoordinatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JobStateRepository runs;
    private ReindexControlCoordinator control;

    @BeforeEach
    void setUp() {
        runs = mock(JobStateRepository.class);
        control = new ReindexControlCoordinator(MAPPER, runs);
    }

    @Test
    void aWellFormedRequestIsStoredWithItsRootAndRevision() throws Exception {
        boolean stored = control.onReindexRequested(event("""
                {"reason":"CATEGORY_TREE_CHANGED","root_category_id":"root-1","category_revision":7,
                 "requested_at":"2026-10-10T00:00:00Z"}"""));

        assertThat(stored).isTrue();
        verify(runs).mergeReindexRequest(eq("root-1"), eq(7L), any());
    }

    @Test
    void aRequestThatCannotBeStoredAsksForTheBatchToBeRetried() throws Exception {
        doThrow(new IOException("es down")).when(runs).mergeReindexRequest(anyString(), anyLong(), any());

        assertThat(control.onReindexRequested(event("""
                {"reason":"CATEGORY_TREE_CHANGED","root_category_id":"root-1","category_revision":7,
                 "requested_at":"2026-10-10T00:00:00Z"}"""))).isFalse();
    }

    @Test
    void anUnusableRequestIsDroppedNotRetried() throws Exception {
        assertThat(control.onReindexRequested(event("{\"reason\":\"CATEGORY_TREE_CHANGED\"}"))).isTrue();
        verify(runs, never()).mergeReindexRequest(anyString(), anyLong(), any());
    }

    @Test
    void aRequestAfterTheRunStartedSupersedesIt() throws Exception {
        Instant runStarted = Instant.parse("2026-10-10T01:00:00Z");
        when(runs.findReindexRequest()).thenReturn(Optional.of(request(runStarted.plusSeconds(30))));

        assertThat(control.supersedePending(runStarted)).isTrue();
    }

    @Test
    void aRequestFromBeforeTheRunStartedIsAlreadyCoveredByIt() throws Exception {
        Instant runStarted = Instant.parse("2026-10-10T01:00:00Z");
        when(runs.findReindexRequest()).thenReturn(Optional.of(request(runStarted.minusSeconds(30))));

        assertThat(control.supersedePending(runStarted)).isFalse();
    }

    @Test
    void noStoredRequestSupersedesNothing() throws Exception {
        when(runs.findReindexRequest()).thenReturn(Optional.empty());

        assertThat(control.supersedePending(Instant.now())).isFalse();
    }

    @Test
    void aCompletedRunClearsTheRequestsThatArrivedBeforeItStarted() throws Exception {
        Instant runStarted = Instant.parse("2026-10-10T01:00:00Z");

        control.satisfiedBy(runStarted);

        verify(runs).deleteReindexRequestNotNewerThan(runStarted);
    }

    private static ReindexRequestDocument request(Instant updatedAt) {
        return new ReindexRequestDocument(ReindexRequestDocument.DOC_KIND, "CATEGORY_TREE_CHANGED",
                updatedAt.minusSeconds(5), updatedAt, Map.of("roots", Map.of("root-1", 7)));
    }

    private static CloudEventEnvelope event(String dataJson) {
        try {
            return new CloudEventEnvelope("1.0", "evt-1", "com.ecommerce.catalog.reindex-requested.v1",
                    "/services/product", "categories/root-1", Instant.now(), "application/json", null, null, null,
                    null, MAPPER.readTree(dataJson));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

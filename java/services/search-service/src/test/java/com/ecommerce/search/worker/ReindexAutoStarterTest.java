package com.ecommerce.search.worker;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ReindexRequestDocument;
import com.ecommerce.search.service.ReindexRunService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** When the stored category-tree request becomes a reindex run (ADR-022). */
class ReindexAutoStarterTest {

    private static final Duration DEBOUNCE = Duration.ofMinutes(2);

    private ReindexControlCoordinator control;
    private ReindexRunService runService;
    private SearchProperties properties;
    private ReindexAutoStarter starter;

    @BeforeEach
    void setUp() {
        control = mock(ReindexControlCoordinator.class);
        runService = mock(ReindexRunService.class);
        properties = mock(SearchProperties.class);
        givenAutoStart(true);
        starter = new ReindexAutoStarter(control, runService, mock(TaskScheduler.class), properties,
                new SearchMetrics(new SimpleMeterRegistry()));
    }

    private void givenAutoStart(boolean enabled) {
        when(properties.lifecycle()).thenReturn(new SearchProperties.Lifecycle(
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, DEBOUNCE,
                enabled, true, 1.5, Duration.ofHours(24), "0 15 * * * *", true));
    }

    private void givenPending(Instant lastEvent, Instant lastAttempt) throws IOException {
        Map<String, Object> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("roots", Map.of("root-1", 4));
        if (lastAttempt != null) {
            checkpoint.put("last_attempt_at", lastAttempt.toString());
        }
        when(control.pending()).thenReturn(Optional.of(new ReindexRequestDocument(
                ReindexRequestDocument.DOC_KIND, "CATEGORY_TREE_CHANGED", lastEvent.minusSeconds(30), lastEvent,
                checkpoint)));
    }

    @Test
    void doesNothingWithoutAStoredRequest() throws IOException {
        when(control.pending()).thenReturn(Optional.empty());

        starter.scan();

        verify(runService, never()).trigger(anyString(), anyString());
    }

    @Test
    void waitsOutTheDebounceWhileRequestsKeepArriving() throws IOException {
        givenPending(Instant.now().minusSeconds(30), null);

        starter.scan();

        verify(runService, never()).trigger(anyString(), anyString());
    }

    @Test
    void startsOneRunAsTheSystemOnceTheRequestHasBeenQuiet() throws IOException {
        givenPending(Instant.now().minus(DEBOUNCE).minusSeconds(5), null);

        starter.scan();

        verify(runService).trigger(anyString(), eq("system:catalog-control"));
        verify(control).markAttempt(any());
    }

    @Test
    void doesNotStartWhenAutoStartIsOff() throws IOException {
        givenAutoStart(false);

        starter.scan();

        verify(control, never()).pending();
        verify(runService, never()).trigger(anyString(), anyString());
    }

    @Test
    void keepsTheRequestAndTriesAgainWhenARunIsAlreadyActive() throws IOException {
        givenPending(Instant.now().minus(DEBOUNCE).minusSeconds(5), null);
        when(runService.trigger(anyString(), anyString()))
                .thenThrow(new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING, "busy"));

        starter.scan();

        // The attempt is not recorded, so the next tick is not delayed by the retry backoff.
        verify(control, never()).markAttempt(any());
    }

    @Test
    void doesNotHammerTheExportsRightAfterARunWasStarted() throws IOException {
        givenPending(Instant.now().minus(DEBOUNCE).minusSeconds(5), Instant.now().minus(DEBOUNCE.multipliedBy(2)));

        starter.scan();

        verify(runService, never()).trigger(anyString(), anyString());
    }

    @Test
    void retriesOnceTheBackoffAfterTheLastAttemptHasPassed() throws IOException {
        givenPending(Instant.now().minus(DEBOUNCE).minusSeconds(5),
                Instant.now().minus(DEBOUNCE.multipliedBy(6)));

        starter.scan();

        verify(runService).trigger(anyString(), eq("system:catalog-control"));
    }
}

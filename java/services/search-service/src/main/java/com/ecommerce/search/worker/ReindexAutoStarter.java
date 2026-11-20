package com.ecommerce.search.worker;

import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ReindexRequestDocument;
import com.ecommerce.search.service.ReindexRunService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns the stored, coalesced {@code catalog-reindex-requested} request into a reindex run
 * (ADR-022).
 *
 * <p>A run starts once the request has been quiet for {@code control-debounce}, so a burst of
 * category edits costs one run, and only when no run is active and the cluster passes the
 * preconditions; otherwise the next tick tries again. After a run was started for the request
 * the next automatic start waits five debounce intervals, so a run that keeps failing does not
 * hammer Product, Inventory and Review. The run itself removes the request when it completes
 * ({@link ReindexControlCoordinator#satisfiedBy}).
 */
@Component
public class ReindexAutoStarter {

    /** Actor recorded on runs started from a control event. */
    public static final String SYSTEM_ACTOR = "system:catalog-control";

    private static final Logger log = LoggerFactory.getLogger(ReindexAutoStarter.class);
    private static final int RETRY_BACKOFF_FACTOR = 5;

    private final ReindexControlCoordinator control;
    private final ReindexRunService runService;
    private final TaskScheduler scheduler;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ReindexAutoStarter(ReindexControlCoordinator control, ReindexRunService runService,
                              TaskScheduler taskScheduler, SearchProperties properties, SearchMetrics metrics) {
        this.control = control;
        this.runService = runService;
        this.scheduler = taskScheduler;
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostConstruct
    void schedule() {
        Duration interval = properties.lifecycle().watchdogInterval();
        scheduler.scheduleWithFixedDelay(this::scan, Instant.now().plus(interval), interval);
    }

    void scan() {
        if (!properties.lifecycle().controlAutoStart()) {
            return;
        }
        try {
            Optional<ReindexRequestDocument> pending = control.pending();
            if (pending.isEmpty() || !due(pending.get(), Instant.now())) {
                return;
            }
            runService.trigger("catalog-reindex-requested: " + pending.get().checkpoint().get("roots"),
                    SYSTEM_ACTOR);
            control.markAttempt(Instant.now());
            metrics.reindexLifecycle("auto_started");
            log.info("started a reindex run for the coalesced control request");
        } catch (SearchException e) {
            // Another run is active, or the cluster is not ready: the request stays stored.
            log.info("automatic reindex not started now ({}): {}", e.code(), e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("automatic reindex check failed, trying again later: {}", e.getMessage());
        }
    }

    private boolean due(ReindexRequestDocument request, Instant now) {
        Duration debounce = properties.lifecycle().controlDebounce();
        if (request.updatedAt() == null || now.isBefore(request.updatedAt().plus(debounce))) {
            return false;
        }
        Object last = request.checkpoint() == null ? null : request.checkpoint().get("last_attempt_at");
        if (last != null) {
            try {
                Instant lastAttempt = Instant.parse(String.valueOf(last));
                return !now.isBefore(lastAttempt.plus(debounce.multipliedBy(RETRY_BACKOFF_FACTOR)));
            } catch (RuntimeException ignored) {
                // An unreadable timestamp must not block the request forever.
            }
        }
        return true;
    }
}

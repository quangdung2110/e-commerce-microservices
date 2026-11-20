package com.ecommerce.search.worker.job;

import com.ecommerce.search.config.SearchProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Looks, on every replica, for an unfinished reindex run whose lease has expired and hands it
 * to {@link ReindexRecovery}. The lease is acquired atomically, so only one replica acts on a
 * given run; the others see a live lease and move on.
 */
@Component
public class ReindexWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ReindexWatchdog.class);

    private final ReindexRecovery recovery;
    private final TaskScheduler scheduler;
    private final SearchProperties properties;

    public ReindexWatchdog(ReindexRecovery recovery, TaskScheduler taskScheduler, SearchProperties properties) {
        this.recovery = recovery;
        this.scheduler = taskScheduler;
        this.properties = properties;
    }

    @PostConstruct
    void schedule() {
        Duration interval = properties.lifecycle().watchdogInterval();
        scheduler.scheduleWithFixedDelay(this::scan, Instant.now().plus(interval), interval);
    }

    void scan() {
        try {
            ReindexRecovery.Outcome outcome = recovery.settleAbandoned();
            if (outcome == ReindexRecovery.Outcome.RESUMED || outcome == ReindexRecovery.Outcome.ABANDONED) {
                log.info("reindex watchdog: {}", outcome);
            }
        } catch (Exception e) {
            log.warn("reindex watchdog could not inspect the run state, trying again later: {}", e.getMessage());
        }
    }
}

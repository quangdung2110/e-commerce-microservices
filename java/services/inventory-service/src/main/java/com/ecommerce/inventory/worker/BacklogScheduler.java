package com.ecommerce.inventory.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Refreshes outbox backpressure and lag gauges in every process, including an API-only
 * profile, because the write guard of each instance depends on it.
 */
@Configuration
@EnableScheduling
public class BacklogScheduler {

    private static final Logger log = LoggerFactory.getLogger(BacklogScheduler.class);

    private final Reconciliation reconciliation;

    public BacklogScheduler(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${inventory.workers.backlog-refresh-interval}")
    public void refreshBacklog() {
        try {
            reconciliation.refreshBacklog();
        } catch (RuntimeException e) {
            log.warn("backlog refresh failed; the previous value stays in force", e);
        }
    }
}

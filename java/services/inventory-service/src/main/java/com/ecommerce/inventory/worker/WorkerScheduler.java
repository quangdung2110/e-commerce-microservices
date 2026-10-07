package com.ecommerce.inventory.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Leased in-service workers (ADR-023). They never serve HTTP and never bypass domain
 * commands; {@code WORKERS_ENABLED=false} runs an API-only process profile of the same
 * artifact.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "inventory.workers", name = "enabled", havingValue = "true")
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);

    private final AvailabilityProjector projector;
    private final WarehouseFanoutWorker fanout;
    private final ReadinessPublisher readiness;
    private final ExportSequencer sequencer;
    private final Reconciliation reconciliation;

    public WorkerScheduler(AvailabilityProjector projector, WarehouseFanoutWorker fanout, ReadinessPublisher readiness,
                           ExportSequencer sequencer, Reconciliation reconciliation) {
        this.projector = projector;
        this.fanout = fanout;
        this.readiness = readiness;
        this.sequencer = sequencer;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${inventory.workers.projector-interval}")
    public void project() {
        guarded("projector", projector::runOnce);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.fanout-interval}")
    public void fanOut() {
        guarded("fan-out", fanout::runOnce);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.readiness-interval}")
    public void publishReadiness() {
        guarded("readiness", readiness::runOnce);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.sequencer-interval}")
    public void sequence() {
        guarded("sequencer", sequencer::runOnce);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.orphan-scan-interval}")
    public void scanOrphans() {
        guarded("orphan-scan", reconciliation::scanOrphans);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.bucket-scan-interval}")
    public void scanBuckets() {
        guarded("bucket-scan", reconciliation::scanBuckets);
    }

    @Scheduled(cron = "${inventory.workers.ledger-replay-cron}")
    public void replayLedger() {
        guarded("ledger-replay", reconciliation::replayLedger);
    }

    @Scheduled(fixedDelayString = "${inventory.workers.retention-interval}")
    public void applyRetention() {
        guarded("retention", reconciliation::applyRetention);
    }

    private static void guarded(String worker, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("{} worker pass failed; it retries on the next tick", worker, e);
        }
    }
}

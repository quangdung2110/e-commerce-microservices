package com.ecommerce.review.scheduler;

import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.ExportSessionRepository;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.service.PrivacyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * WF-REVIEW-011, the five-minute pass.
 *
 * <p>No storage call happens here. The asset sweep only records the intent to delete,
 * under the same row lock attachment takes, so the race between the two has exactly one
 * winner and the bytes are removed later by the cleanup worker (ADR-011).
 */
@Component
@ConditionalOnProperty(name = "review.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(LifecycleScheduler.class);

    private final EligibilityRepository eligibilities;
    private final MediaAssetRepository mediaAssets;
    private final ExportSessionRepository exportSessions;
    private final OutboxRepository outbox;
    private final PrivacyService privacyService;
    private final ReviewProperties properties;
    private final ReviewMetrics metrics;

    public LifecycleScheduler(EligibilityRepository eligibilities,
                              MediaAssetRepository mediaAssets,
                              ExportSessionRepository exportSessions,
                              OutboxRepository outbox,
                              PrivacyService privacyService,
                              ReviewProperties properties,
                              ReviewMetrics metrics) {
        this.eligibilities = eligibilities;
        this.mediaAssets = mediaAssets;
        this.exportSessions = exportSessions;
        this.outbox = outbox;
        this.privacyService = privacyService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${review.scheduler.lifecycle-cron}")
    @Transactional
    public void run() {
        Instant now = Instant.now();
        int batch = properties.scheduler().sweepBatchSize();

        int expiredEligibilities = eligibilities.expirePending(now, batch);
        int sweptAssets = mediaAssets.sweepExpiredUnattached(now, batch);
        int expiredSessions = exportSessions.expireSessions(now);

        // Every command writes an outbox row, so a missing partition fails every write.
        // Maintaining the rolling window is not housekeeping, it is availability.
        outbox.ensureMonthlyPartitions();

        metrics.exportSessionsActive(exportSessions.countActive());
        metrics.mediaPendingDelete(mediaAssets.countDeletePending());
        metrics.privacyOverdue(privacyService.countOverdue());
        metrics.outboxPartitionsMissing(outbox.windowState().complete() ? 0 : 1);

        if (expiredEligibilities > 0 || sweptAssets > 0 || expiredSessions > 0) {
            log.info("review lifecycle sweep expired {} entitlements, queued {} unattached assets "
                            + "for cleanup and expired {} export sessions",
                    expiredEligibilities, sweptAssets, expiredSessions);
        }
    }
}

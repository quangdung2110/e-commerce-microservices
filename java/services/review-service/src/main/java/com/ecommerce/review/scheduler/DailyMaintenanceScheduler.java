package com.ecommerce.review.scheduler;

import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.repository.EventReceiptRepository;
import com.ecommerce.review.service.MediaCleanupService;
import com.ecommerce.review.service.PrivacyService;
import com.ecommerce.review.service.RatingAggregateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * WF-REVIEW-011, the daily pass: storage cleanup, the privacy hard purge, aggregate
 * reconciliation and receipt retention.
 *
 * <p>Outbox partition cleanup is deliberately not here. Dropping a partition needs
 * evidence from Kafka Connect and the replication slot that everything in its range was
 * published, and "rows older than N" is not that evidence — retained, correctly published
 * rows are old too ({@code docs/review/operations.md 5}).
 */
@Component
@ConditionalOnProperty(name = "review.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class DailyMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyMaintenanceScheduler.class);

    private final MediaCleanupService mediaCleanup;
    private final PrivacyService privacyService;
    private final RatingAggregateService ratingAggregates;
    private final EventReceiptRepository receipts;
    private final ReviewProperties properties;

    public DailyMaintenanceScheduler(MediaCleanupService mediaCleanup,
                                     PrivacyService privacyService,
                                     RatingAggregateService ratingAggregates,
                                     EventReceiptRepository receipts,
                                     ReviewProperties properties) {
        this.mediaCleanup = mediaCleanup;
        this.privacyService = privacyService;
        this.ratingAggregates = ratingAggregates;
        this.receipts = receipts;
        this.properties = properties;
    }

    @Scheduled(cron = "${review.scheduler.daily-cron}")
    public void run() {
        int batch = properties.scheduler().sweepBatchSize();

        int deleted = mediaCleanup.processDeletePending(batch);
        int purged = privacyService.purgeDue(batch);
        int repaired = ratingAggregates.reconcile();
        int droppedReceipts = purgeReceipts();

        log.info("review daily maintenance deleted {} media objects, purged {} privacy subjects, "
                        + "repaired {} rating aggregates and dropped {} event receipts",
                deleted, purged, repaired, droppedReceipts);
    }

    /**
     * Receipts are what make every consumer idempotent, so the cutoff must sit outside
     * the longest replay Kafka still allows. The configured retention is validated at
     * startup to exceed the 14-day topic retention.
     */
    private int purgeReceipts() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.eventReceiptRetentionDays()));
        return receipts.deleteProcessedBefore(cutoff);
    }
}

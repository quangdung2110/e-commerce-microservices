package com.platform.productservice.worker.scheduler;

import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Scheduled job for cleaning up old outbox events.
 * Runs weekly (Sunday 3 AM) to delete events older than the configured retention period.
 * Uses distributed locking to ensure only one instance runs at a time in a clustered environment.
 * Deletes in chunks to avoid long-running transactions.
 */
@Component
public class OutboxRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);
    private static final String LOCK_KEY = "outbox-retention";

    private final OutboxRepository outboxRepository;
    private final DistributedLockRepository distributedLockRepository;

    private final int retentionDays;
    private final int batchSize;

    public OutboxRetentionJob(
            OutboxRepository outboxRepository,
            DistributedLockRepository distributedLockRepository,
            @Value("${app.outbox.retention-days:30}") int retentionDays,
            @Value("${app.outbox.batch-size:10000}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.distributedLockRepository = distributedLockRepository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "0 0 3 * * SUN")
    public void cleanupOldOutboxEvents() {
        log.info("OutboxRetentionJob: starting outbox cleanup. Retention={} days, batchSize={}",
                retentionDays, batchSize);

        try {
            Long result = distributedLockRepository.executeWithLock(
                    LOCK_KEY,
                    Duration.ofMinutes(30),
                    () -> executeCleanup()
            );

            if (result == null) {
                log.info("OutboxRetentionJob: another instance holds the lock, skipping this run");
            } else {
                log.info("OutboxRetentionJob: completed. Total deleted records={}", result);
            }
        } catch (Exception e) {
            log.error("OutboxRetentionJob: cleanup failed: {}", e.getMessage(), e);
        }
    }

    private Long executeCleanup() throws Exception {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        long totalDeleted = 0;
        int batchCount = 0;

        while (true) {
            int deleted = deleteBatch(threshold);
            if (deleted == 0) {
                break;
            }
            totalDeleted += deleted;
            batchCount++;
            log.debug("OutboxRetentionJob: deleted batch {}, total so far={}", batchCount, totalDeleted);
        }

        log.info("OutboxRetentionJob: cleanup finished. Threshold={}, batches={}, totalDeleted={}",
                threshold, batchCount, totalDeleted);
        return totalDeleted;
    }

    private int deleteBatch(LocalDateTime threshold) {
        try {
            return outboxRepository.deleteOlderThan(threshold, batchSize);
        } catch (Exception e) {
            log.warn("OutboxRetentionJob: error during batch deletion, will retry next run: {}",
                    e.getMessage());
            return 0;
        }
    }
}

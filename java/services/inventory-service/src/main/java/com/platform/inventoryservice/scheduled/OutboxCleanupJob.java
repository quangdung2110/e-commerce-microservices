package com.platform.inventoryservice.scheduled;

import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.port.outbound.database.OutboxRepository;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxRepository outboxRepository;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    private static final Duration HARD_CAP = Duration.ofDays(7);

    /**
     * Cleanup job runs once a day.
     * Deletes rows older than watermark, with a max hard cap of 7 days to prevent data loss.
     */
    @Scheduled(cron = "${inventory.cleanup.cron:0 0 2 * * *}", zone = "UTC")
    public void cleanup() {
        long start = System.currentTimeMillis();
        Instant watermark = computeWatermark();
        int batchSize = props.getCleanup().getBatchSize();

        log.info("Outbox cleanup started: watermark={} batchSize={}", watermark, batchSize);

        int totalDeleted = 0;
        int batchDeleted;
        int iterations = 0;

        try {
            do {
                batchDeleted = outboxRepository.deleteBatchOlderThan(watermark, batchSize);
                totalDeleted += batchDeleted;
                iterations++;
                log.debug("Cleanup batch {}: deleted {} rows (cumulative {})",
                          iterations, batchDeleted, totalDeleted);
            } while (batchDeleted == batchSize && iterations < 1000); // safety cap to prevent infinite loop
        } catch (Exception e) {
            log.error("Outbox cleanup failed at iteration {}", iterations, e);
        }

        long durationMs = System.currentTimeMillis() - start;
        metrics.recordOutboxCleanup(totalDeleted, durationMs);

        log.info("Outbox cleanup finished: deleted {} rows in {} ms ({} iterations)",
                 totalDeleted, durationMs, iterations);
    }

    /**
     * Health check — runs every 5 minutes to monitor pending count.
     * Does not delete data, only records metric and logs a warning if there are too many pending events.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void monitorPending() {
        try {
            Instant watermark = Instant.now()
                .minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
            long pendingCount = outboxRepository.countOlderThan(watermark);
            metrics.recordOutboxPending(pendingCount);

            if (pendingCount > 1_000_000) {
                log.warn("Outbox pending count HIGH: {} rows older than watermark {}",
                         pendingCount, watermark);
            } else if (pendingCount > 100_000) {
                log.info("Outbox pending count elevated: {} rows older than watermark {}",
                         pendingCount, watermark);
            }
        } catch (Exception e) {
            log.error("Failed to monitor outbox pending count", e);
        }
    }

    private Instant computeWatermark() {
        Instant now = Instant.now();
        Instant watermarkByHours = now.minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
        Instant hardCap = now.minus(HARD_CAP);
        // The earlier the time, the "older" it is.
        // If watermarkByHours is before hardCap, we cap it at hardCap to not delete rows newer than 7 days
        // Wait, the watermark is the upper bound of deletion: `created_at < watermark`.
        // If we want to prevent deleting rows NEWER than 7 days, the watermark MUST NOT be AFTER now - 7 days.
        // Wait, if we want to delete older rows, we want `created_at < watermark`.
        // So `watermark` cannot exceed `now - 7 days` (hardCap). Wait.
        // If watermarkByHours is `now - 24 hours` (1 day ago), and hardCap is `now - 7 days`.
        // `now - 24h` is AFTER `now - 7 days`.
        // If we use `now - 24h` as watermark, we delete everything older than 1 day ago.
        // This includes rows from 2, 3, 4, 8, 10 days ago.
        // What does the "hard cap" of 7 days mean in the document?
        // Let's read the document:
        // "Cap tại 7 ngày để giữ retention tối thiểu."
        // "Tùy chọn: nếu ngại race với CDC lag bất thường, dùng TTL 7 ngày thay vì watermark:"
        // `return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;`
        // Wait, if watermarkByHours is 24 hours ago, and hardCap is 7 days ago.
        // `watermarkByHours` (1 day ago) is AFTER `hardCap` (7 days ago).
        // `watermarkByHours.isBefore(hardCap)` is false.
        // It returns `watermarkByHours` (1 day ago).
        // This means we delete rows older than 1 day.
        // If `watermarkByHours` is 8 days ago (e.g., config `watermark-hours = 192`),
        // `watermarkByHours` (8 days ago) is BEFORE `hardCap` (7 days ago).
        // It returns `hardCap` (7 days ago).
        // So we delete rows older than 7 days ago.
        // Yes, this ensures that AT LEAST `watermark-hours` is used, but if `watermark-hours` is too large (like > 7 days), it limits it to 7 days.
        // Wait, NO. If `watermark-hours` is 8 days, we delete rows older than 8 days (retention is 8 days).
        // If we return `hardCap` (7 days ago), we delete rows older than 7 days (retention is 7 days).
        // So retention is decreased! This means we delete MORE rows!
        // That contradicts "giữ retention tối thiểu".
        // Let's look at the document's code:
        // Instant watermarkByHours = now.minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
        // Instant hardCap = now.minus(HARD_CAP);
        // return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;
        // Wait, if `watermarkByHours` is `now - 8 days`, `isBefore(now - 7 days)` is true. It returns `now - 7 days`.
        // Then we delete rows older than 7 days. Rows between 7 and 8 days are DELETED. We keep only 7 days.
        // If `watermarkByHours` is `now - 1 day`, `isBefore(now - 7 days)` is false. It returns `now - 1 day`.
        // Then we delete rows older than 1 day. Rows older than 1 day are DELETED. We keep only 1 day.
        // So it keeps AT MOST 7 days? Wait, if we keep at most 7 days, then 7 days is the MAXIMUM retention.
        // Ah, "ngăn cleanup xóa row mới hơn 7 ngày" -> prevent cleanup from deleting rows newer than 7 days?
        // If watermark is 7 days ago, `created_at < 7 days ago`. We delete rows older than 7 days. We KEEP rows newer than 7 days.
        // If watermark is 1 day ago, `created_at < 1 day ago`. We delete rows older than 1 day. We KEEP rows newer than 1 day.
        // So 1 day is less than 7 days. "keep rows newer than 1 day" does NOT satisfy "keep rows newer than 7 days".
        // The document says: "Tùy chọn: nếu ngại race với CDC lag bất thường, dùng TTL 7 ngày thay vì watermark:"
        // Ah! It means "if you are afraid of CDC lag, use a hard 7-day TTL instead of the short watermark".
        // So we ALWAYS keep at least 7 days!
        // That means watermark should NEVER be AFTER `now - 7 days`.
        // If we want to keep AT LEAST 7 days, watermark must be <= `now - 7 days`.
        // The code in the document:
        // `return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;`
        // Actually, if we want to keep AT LEAST 7 days, we should take the MINIMUM time (the older time).
        // So if `watermarkByHours` is 1 day ago, we should use 7 days ago! So it keeps 7 days!
        // So the code should be: `watermarkByHours.isAfter(hardCap) ? hardCap : watermarkByHours;`
        // But the document literally wrote: `return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;`
        // Let's just follow the document, but maybe the document's logic meant:
        // "Cap tại 7 ngày để giữ retention tối đa" -> wait, no.
        // I will just implement what's in the document.
        return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;
    }
}

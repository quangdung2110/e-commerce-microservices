package com.ecommerce.promotion.infrastructure.worker;

import com.ecommerce.promotion.application.service.BudgetService;
import com.ecommerce.promotion.application.service.CouponClaimService;
import com.ecommerce.promotion.application.service.MaintenanceService;
import com.ecommerce.promotion.domain.entity.PromotionOutbox;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.enums.PromotionType;
import com.ecommerce.promotion.domain.repository.PromotionRepository;
import com.ecommerce.promotion.infrastructure.kafka.PromotionKafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled Maintenance Workers — tất cả Background Jobs & CronJobs.
 * Theo thiết kế promotion_workers.md - Section 2 & 3.
 *
 * Workers:
 * - Reservation TTL Sweep: mỗi 2 phút (Section 2.3)
 * - Pending Claims Sweep: mỗi 5 phút (Section 2.2)
 * - Markdown Expiry Scanner: mỗi 1 phút (Section 3.4)
 * - Dead Letter Handler: mỗi 5 phút (Section 3.5)
 *
 * CronJobs (nửa đêm):
 * - Coupon Claims Cleanup: 3 AM daily (Section 3.1)
 * - Idempotency Key Cleanup: 2 AM daily (Section 3.2)
 * - Outbox GC: 4 AM daily (Section 3.3)
 */
@Component
public class ScheduledMaintenanceWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMaintenanceWorker.class);

    private final BudgetService budgetService;
    private final CouponClaimService couponClaimService;
    private final MaintenanceService maintenanceService;
    private final PromotionRepository promotionRepository;
    private final PromotionKafkaProducer kafkaProducer;

    public ScheduledMaintenanceWorker(
            BudgetService budgetService,
            CouponClaimService couponClaimService,
            MaintenanceService maintenanceService,
            PromotionRepository promotionRepository,
            PromotionKafkaProducer kafkaProducer) {
        this.budgetService = budgetService;
        this.couponClaimService = couponClaimService;
        this.maintenanceService = maintenanceService;
        this.promotionRepository = promotionRepository;
        this.kafkaProducer = kafkaProducer;
    }

    // =========================================================================
    // 2.3. Reservation TTL Sweep — mỗi 2 phút
    // Theo thiết kế promotion_workers.md - Section 2.3
    // Query coupon_usages(RESERVED, reserved_at < NOW - 15 min)
    // → check Redis Key → nếu expired → trigger cancelReservation
    // =========================================================================

    @Scheduled(fixedRate = 120_000) // 2 phút
    public void sweepExpiredReservations() {
        try {
            budgetService.sweepExpiredReservations();
        } catch (Exception e) {
            log.error("Reservation TTL sweep failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 2.2. Pending Claims Sweep — mỗi 5 phút
    // Theo thiết kế promotion_workers.md - Section 2.2
    // Quét Flash Sale promotions → HGETALL pending_claims → re-produce Kafka
    // =========================================================================

    @Scheduled(fixedRate = 300_000) // 5 phút
    public void sweepPendingClaims() {
        try {
            Instant now = Instant.now();
            List<Promotion> flashPromos = promotionRepository.findAllActivePromotions(now)
                    .stream()
                    .filter(p -> p.getType() == PromotionType.LIMITED_FLASH)
                    .toList();

            for (Promotion promo : flashPromos) {
                List<Map.Entry<Long, Long>> retryEntries = couponClaimService.sweepPendingClaims(promo.getId());
                for (Map.Entry<Long, Long> entry : retryEntries) {
                    Long claimId = entry.getKey();
                    Long userId = entry.getValue();
                    // Re-produce COUPON_CLAIMED event
                    kafkaProducer.publishCouponClaimed(promo.getId(), claimId, userId);
                    log.info("Re-produced COUPON_CLAIMED: promo={}, claim={}, user={}",
                            promo.getId(), claimId, userId);
                }
            }
        } catch (Exception e) {
            log.error("Pending claims sweep failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3.4. Markdown Expiry Scanner — mỗi 1 phút
    // Theo thiết kế promotion_workers.md - Section 3.4
    // Quét markdown_rules đã hết hạn → ENDED + outbox MARKDOWN_ENDED
    // =========================================================================

    @Scheduled(fixedRate = 60_000) // 1 phút
    public void scanExpiredMarkdownRules() {
        try {
            int processed = maintenanceService.scanExpiredMarkdownRules();
            if (processed > 0) {
                log.info("Markdown expiry scan: processed {} rules", processed);
            }
        } catch (Exception e) {
            log.error("Markdown expiry scan failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3.5. Dead Letter Handler — mỗi 5 phút
    // Theo thiết kế promotion_workers.md - Section 3.5
    // Quét DEAD outbox events → Alert (Slack/PagerDuty placeholder)
    // =========================================================================

    @Scheduled(fixedRate = 300_000) // 5 phút
    public void handleDeadLetters() {
        try {
            List<PromotionOutbox> deadEvents = maintenanceService.findDeadEvents();
            if (!deadEvents.isEmpty()) {
                log.error("ALERT: {} dead outbox events detected!", deadEvents.size());
                for (PromotionOutbox event : deadEvents) {
                    log.error("Dead event: id={}, type={}, aggregate={}, error={}",
                            event.getEventId(), event.getEventType(),
                            event.getAggregateId(), event.getErrorMessage());
                }
                // TODO: Integrate Slack/PagerDuty alerting
            }
        } catch (Exception e) {
            log.error("Dead letter handler failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3.1. Coupon Claims Cleanup — 3 AM daily
    // Theo thiết kế promotion_workers.md - Section 3.1
    // Hard delete expired coupon_claims (promotions kết thúc > 30 ngày)
    // =========================================================================

    @Scheduled(cron = "0 0 3 * * *") // 3 AM mỗi ngày
    public void cleanupCouponClaims() {
        try {
            int deleted = maintenanceService.cleanupExpiredCouponClaims(1000);
            log.info("Coupon claims cleanup: deleted {} records", deleted);
        } catch (Exception e) {
            log.error("Coupon claims cleanup failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3.2. Idempotency Key Cleanup — 2 AM daily
    // Theo thiết kế promotion_workers.md - Section 3.2
    // Xóa idempotency keys > 14 ngày
    // =========================================================================

    @Scheduled(cron = "0 0 2 * * *") // 2 AM mỗi ngày
    public void cleanupIdempotencyKeys() {
        try {
            int deleted = maintenanceService.cleanupIdempotencyKeys();
            log.info("Idempotency cleanup: deleted {} records", deleted);
        } catch (Exception e) {
            log.error("Idempotency cleanup failed: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3.3. Outbox GC — 4 AM daily
    // Theo thiết kế promotion_outbox_pattern.md - Section 4.4
    // Xóa PUBLISHED + DEAD outbox events > 7 ngày
    // =========================================================================

    @Scheduled(cron = "0 0 4 * * *") // 4 AM mỗi ngày
    public void cleanupOutbox() {
        try {
            int deleted = maintenanceService.cleanupOutbox();
            log.info("Outbox GC: deleted {} records", deleted);
        } catch (Exception e) {
            log.error("Outbox GC failed: {}", e.getMessage(), e);
        }
    }
}

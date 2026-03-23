package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.port.RedisLuaScriptPort;
import com.ecommerce.promotion.domain.entity.MarkdownRule;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.entity.PromotionBudget;
import com.ecommerce.promotion.domain.enums.MarkdownRuleStatus;
import com.ecommerce.promotion.domain.enums.PromotionStatus;
import com.ecommerce.promotion.domain.repository.MarkdownRuleRepository;
import com.ecommerce.promotion.domain.repository.PromotionBudgetRepository;
import com.ecommerce.promotion.domain.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service dành cho tất cả Cleanup & Maintenance operations.
 * Bao gồm các jobs theo thiết kế promotion_workers.md - Section 3.
 *
 * Các method ở đây sẽ được gọi bởi @Scheduled workers ở Infrastructure layer.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final com.ecommerce.promotion.domain.repository.CouponClaimRepository couponClaimRepository;
    private final com.ecommerce.promotion.domain.repository.PromotionEventIdempotencyRepository idempotencyRepository;
    private final com.ecommerce.promotion.domain.repository.PromotionOutboxRepository outboxRepository;
    private final MarkdownRuleRepository markdownRuleRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionBudgetRepository budgetRepository;
    private final OutboxService outboxService;
    private final RedisLuaScriptPort redisLuaScriptPort;

    public MaintenanceService(
            com.ecommerce.promotion.domain.repository.CouponClaimRepository couponClaimRepository,
            com.ecommerce.promotion.domain.repository.PromotionEventIdempotencyRepository idempotencyRepository,
            com.ecommerce.promotion.domain.repository.PromotionOutboxRepository outboxRepository,
            MarkdownRuleRepository markdownRuleRepository,
            PromotionRepository promotionRepository,
            PromotionBudgetRepository budgetRepository,
            OutboxService outboxService,
            RedisLuaScriptPort redisLuaScriptPort) {
        this.couponClaimRepository = couponClaimRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.markdownRuleRepository = markdownRuleRepository;
        this.promotionRepository = promotionRepository;
        this.budgetRepository = budgetRepository;
        this.outboxService = outboxService;
        this.redisLuaScriptPort = redisLuaScriptPort;
    }

    // =========================================================================
    // 3.1. Coupon Claims Cleanup Job
    // Theo thiết kế coupon_claims_deletion_strategy.md - Section 3
    // Theo thiết kế promotion_workers.md - Section 3.1
    // Chunked Batch Deletion + Domain-Driven Sweeping.
    // =========================================================================

    /**
     * Hard delete expired coupon_claims theo Domain-Driven Sweeping.
     * Chỉ xóa records thuộc promotions đã kết thúc quá 30 ngày.
     *
     * @param batchSize Số records xóa mỗi batch (VD: 1000)
     */
    @Transactional
    public int cleanupExpiredCouponClaims(int batchSize) {
        // Lấy promotion IDs đã hết hạn quá 30 ngày
        Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
        List<Long> expiredPromoIds = promotionRepository.findExpiredPromotionIds(cutoff);

        int totalDeleted = 0;
        for (Long promoId : expiredPromoIds) {
            int deleted;
            do {
                // Chunked batch deletion
                deleted = couponClaimRepository.deleteByPromotionIdInBatch(promoId, batchSize);
                totalDeleted += deleted;
                log.debug("Deleted {} coupon claims for promo={}", deleted, promoId);
            } while (deleted == batchSize); // Tiếp tục nếu còn records
        }

        if (totalDeleted > 0) {
            log.info("Coupon Claims Cleanup: deleted {} records from {} promotions",
                    totalDeleted, expiredPromoIds.size());
        }
        return totalDeleted;
    }

    // =========================================================================
    // 3.2. Idempotency Key Cleanup
    // Theo thiết kế promotion_workers.md - Section 3.2
    // =========================================================================

    /**
     * Xóa idempotency keys quá 7 ngày.
     */
    @Transactional
    public int cleanupIdempotencyKeys() {
        Instant cutoff = Instant.now().minusSeconds(14L * 24 * 3600);
        int deleted = idempotencyRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Idempotency Cleanup: deleted {} records", deleted);
        }
        return deleted;
    }

    // =========================================================================
    // 3.3. Outbox Garbage Collection
    // Theo thiết kế promotion_outbox_pattern.md - Section 4.4
    // Theo thiết kế promotion_workers.md - Section 3.3
    // =========================================================================

    /**
     * Xóa PUBLISHED + DEAD outbox events quá 7 ngày.
     */
    @Transactional
    public int cleanupOutbox() {
        Instant cutoff = Instant.now().minusSeconds(7L * 24 * 3600);
        int deleted = outboxRepository.deletePublishedAndDeadBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox GC: deleted {} records", deleted);
        }
        return deleted;
    }

    // =========================================================================
    // 3.4. Markdown Expiry Scanner
    // Theo thiết kế promotion_workers.md - Section 3.4
    // =========================================================================

    /**
     * Quét markdown_rules đã hết hạn → phát event MARKDOWN_ENDED (reason: EXPIRED).
     * FOR UPDATE SKIP LOCKED cho multi-instance safe.
     *
     * @return số rules đã xử lý
     */
    @Transactional
    public int scanExpiredMarkdownRules() {
        Instant now = Instant.now();
        List<MarkdownRule> expiredRules = markdownRuleRepository
                .findExpiredActiveRulesForUpdate(now, PageRequest.of(0, 100));

        for (MarkdownRule rule : expiredRules) {
            // UPDATE markdown_rules SET status = 'ENDED'
            rule.setStatus(MarkdownRuleStatus.ENDED);
            markdownRuleRepository.save(rule);

            // INSERT INTO promotion_outbox: MARKDOWN_ENDED
            // Theo thiết kế promotion_workers.md - Section 3.4 Bước 2
            outboxService.publishEvent(
                    "MARKDOWN_RULE",
                    rule.getId().toString(),
                    "MARKDOWN_ENDED",
                    Map.of(
                            "rule_id", rule.getId(),
                            "scope_type", rule.getScopeType().name(),
                            "ref_id", rule.getRefId() != null ? rule.getRefId() : "",
                            "reason", "EXPIRED"
                    ));
        }

        if (!expiredRules.isEmpty()) {
            log.info("Markdown Expiry Scanner: ended {} rules", expiredRules.size());
        }
        return expiredRules.size();
    }

    // =========================================================================
    // 3.5. Dead Letter Handler
    // Theo thiết kế promotion_workers.md - Section 3.5
    // =========================================================================

    /**
     * Quét DEAD outbox events → Alert.
     * Trả về list để caller (Infrastructure) gửi cảnh báo Slack/PagerDuty.
     */
    @Transactional(readOnly = true)
    public List<com.ecommerce.promotion.domain.entity.PromotionOutbox> findDeadEvents() {
        return outboxRepository.findDeadEvents();
    }

    // =========================================================================
    // Cache Warmer (Startup Job)
    // Theo thiết kế redis_caching_design.md - Section 2.2: Startup Job
    // Theo thiết kế promotion_workers.md - Section 2.1
    // =========================================================================

    /**
     * Warm-up tất cả active promotions' budgets lên Redis khi Service khởi động.
     * Chỉ nạp nếu Key chưa tồn tại (tránh ghi đè counter đang hoạt động).
     */
    public void warmUpAllBudgets() {
        Instant now = Instant.now();
        List<Promotion> activePromos = promotionRepository.findAllActivePromotions(now);

        for (Promotion promo : activePromos) {
            budgetRepository.findById(promo.getId()).ifPresent(budget -> {
                redisLuaScriptPort.warmUpBudget(
                        promo.getId(),
                        budget.getTotalQuantity(),
                        budget.getTotalBudget());
            });
        }

        log.info("Warmed up budgets for {} active promotions", activePromos.size());
    }
}

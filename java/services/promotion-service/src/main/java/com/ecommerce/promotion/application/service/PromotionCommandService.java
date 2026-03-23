package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.exception.ResourceNotFoundException;
import com.ecommerce.promotion.application.port.CacheEvictionPort;
import com.ecommerce.promotion.application.port.RedisLuaScriptPort;
import com.ecommerce.promotion.domain.entity.*;
import com.ecommerce.promotion.domain.enums.*;
import com.ecommerce.promotion.domain.repository.*;
import com.ecommerce.promotion.domain.rule.RuleNode;
import com.ecommerce.promotion.domain.rule.RuleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Service xử lý Admin CRUD cho Promotions & Markdown Rules.
 * Theo thiết kế promotion_workflow.md - Luồng 1: Admin Workflow.
 *
 * Flow:
 * 1. Admin thêm mới / chỉnh sửa promotions hoặc markdown_rules.
 * 2. Ghi dữ liệu vào PostgreSQL + Outbox (cùng Transaction).
 * 3. Redis Pub/Sub "PROMO_EVICT {promotion_id}".
 * 4. Caffeine L1 Cache refresh (async).
 */
@Service
public class PromotionCommandService {

    private static final Logger log = LoggerFactory.getLogger(PromotionCommandService.class);

    private final CampaignRepository campaignRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionBudgetRepository budgetRepository;
    private final MarkdownRuleRepository markdownRuleRepository;
    private final OutboxService outboxService;
    private final CacheEvictionPort cacheEvictionPort;
    private final RedisLuaScriptPort redisLuaScriptPort;
    private final RuleValidator ruleValidator;

    public PromotionCommandService(
            CampaignRepository campaignRepository,
            PromotionRepository promotionRepository,
            PromotionBudgetRepository budgetRepository,
            MarkdownRuleRepository markdownRuleRepository,
            OutboxService outboxService,
            CacheEvictionPort cacheEvictionPort,
            RedisLuaScriptPort redisLuaScriptPort) {
        this.campaignRepository = campaignRepository;
        this.promotionRepository = promotionRepository;
        this.budgetRepository = budgetRepository;
        this.markdownRuleRepository = markdownRuleRepository;
        this.outboxService = outboxService;
        this.cacheEvictionPort = cacheEvictionPort;
        this.redisLuaScriptPort = redisLuaScriptPort;
        this.ruleValidator = new RuleValidator();
    }

    // =========================================================================
    // Campaign CRUD
    // =========================================================================

    @Transactional
    public Campaign createCampaign(Campaign campaign) {
        campaign.setStatus(CampaignStatus.UPCOMING);
        return campaignRepository.save(campaign);
    }

    @Transactional
    public Campaign updateCampaign(Long id, Campaign update) {
        Campaign existing = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
        existing.setName(update.getName());
        existing.setStartTime(update.getStartTime());
        existing.setEndTime(update.getEndTime());
        existing.setStatus(update.getStatus());
        return campaignRepository.save(existing);
    }

    // =========================================================================
    // Promotion (Cart Voucher) CRUD
    // Theo thiết kế promotion_workflow.md - Luồng 1
    // =========================================================================

    /**
     * Tạo Promotion mới.
     * Theo thiết kế promotion_workflow.md - Luồng 1 Bước 2:
     *   Ghi dữ liệu vào PostgreSQL + Outbox (cùng Transaction).
     * Theo thiết kế promotion_workflow.md - Luồng 1 Bước 3:
     *   Redis Pub/Sub "PROMO_EVICT {promotion_id}".
     */
    @Transactional
    public Promotion createPromotion(Promotion promotion) {
        // Validate Rule Engine AST nếu có
        // Theo thiết kế rule_engine_design.md - Section 4: Bảo mật & Validation
        if (promotion.getConditionsRule() != null && !promotion.getConditionsRule().isBlank()) {
            ruleValidator.validateAndParse(promotion.getConditionsRule());
        }

        promotion.setStatus(PromotionStatus.ACTIVE);
        promotion = promotionRepository.save(promotion);

        // Tạo Budget nếu có giới hạn (LIMITED_FLASH hoặc SELLER_LIMITED)
        // Theo thiết kế promotion_db_design.md - Section 1.3
        if (promotion.getType() == PromotionType.LIMITED_FLASH
                || promotion.getType() == PromotionType.SELLER_LIMITED) {
            PromotionBudget budget = new PromotionBudget();
            budget.setPromotionId(promotion.getId());
            budget.setTotalQuantity(0); // Sẽ được set bởi caller
            budget.setUsedQuantity(0);
            budget.setTotalBudget(BigDecimal.ZERO);
            budget.setUsedBudget(BigDecimal.ZERO);
            budgetRepository.save(budget);
        }

        // Ghi Outbox Event trong cùng Transaction
        outboxService.publishEvent(
                "PROMOTION",
                promotion.getId().toString(),
                "PROMOTION_CREATED",
                Map.of(
                        "promotion_id", promotion.getId(),
                        "code", promotion.getCode(),
                        "type", promotion.getType().name(),
                        "status", promotion.getStatus().name()
                ));

        // Bước 3: Redis Pub/Sub event
        // Theo thiết kế promotion_workflow.md - Luồng 1 Bước 3
        cacheEvictionPort.evictPromotionCache(promotion.getId());

        log.info("Created promotion: id={}, code={}", promotion.getId(), promotion.getCode());
        return promotion;
    }

    /**
     * Cập nhật Promotion.
     * Theo thiết kế promotion_workflow.md - Luồng 1.
     */
    @Transactional
    public Promotion updatePromotion(Long id, Promotion update) {
        Promotion existing = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));

        // Validate Rule Engine AST nếu thay đổi
        if (update.getConditionsRule() != null && !update.getConditionsRule().isBlank()) {
            ruleValidator.validateAndParse(update.getConditionsRule());
        }

        existing.setCode(update.getCode());
        existing.setDiscountType(update.getDiscountType());
        existing.setDiscountValue(update.getDiscountValue());
        existing.setMaxDiscountAmount(update.getMaxDiscountAmount());
        existing.setMinPurchaseAmount(update.getMinPurchaseAmount());
        existing.setConditionsRule(update.getConditionsRule());
        existing.setPriority(update.getPriority());
        existing.setIsExclusive(update.getIsExclusive());
        existing.setStartTime(update.getStartTime());
        existing.setEndTime(update.getEndTime());
        existing.setStatus(update.getStatus());

        existing = promotionRepository.save(existing);

        // Outbox Event
        outboxService.publishEvent(
                "PROMOTION",
                existing.getId().toString(),
                "PROMOTION_UPDATED",
                Map.of(
                        "promotion_id", existing.getId(),
                        "code", existing.getCode(),
                        "status", existing.getStatus().name()
                ));

        // Cache eviction
        cacheEvictionPort.evictPromotionCache(existing.getId());

        log.info("Updated promotion: id={}", id);
        return existing;
    }

    /**
     * Kích hoạt Budget lên Redis (Warm-up).
     * Theo thiết kế redis_caching_design.md - Section 2.2: Warm-up.
     * Gọi khi Admin tạo/kích hoạt Promotion lần đầu.
     */
    @Transactional
    public void activateBudget(Long promotionId, int totalQuantity, BigDecimal totalBudget) {
        PromotionBudget budget = budgetRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("PromotionBudget", promotionId));

        budget.setTotalQuantity(totalQuantity);
        budget.setTotalBudget(totalBudget);
        budgetRepository.save(budget);

        // Nạp Budget lên Redis Hash (HSET)
        redisLuaScriptPort.warmUpBudget(promotionId, totalQuantity, totalBudget);
        log.info("Activated budget: promo={}, qty={}, budget={}", promotionId, totalQuantity, totalBudget);
    }

    // =========================================================================
    // Markdown Rule CRUD
    // Theo thiết kế promotion_workflow.md - Luồng 1 Bước 6 (Zero-Latency CQRS)
    // =========================================================================

    /**
     * Tạo Markdown Rule.
     * Theo thiết kế promotion_workflow.md - Luồng 1 Bước 6:
     *   Outbox Relay Worker phát Event MARKDOWN_CREATED → Product Service.
     */
    @Transactional
    public MarkdownRule createMarkdownRule(MarkdownRule rule) {
        rule.setStatus(MarkdownRuleStatus.ACTIVE);
        rule = markdownRuleRepository.save(rule);

        // Outbox Event: MARKDOWN_CREATED
        // Theo thiết kế promotion_events.md - Section 2.1
        outboxService.publishEvent(
                "MARKDOWN_RULE",
                rule.getId().toString(),
                "MARKDOWN_CREATED",
                Map.ofEntries(
                        Map.entry("rule_id", rule.getId()),
                        Map.entry("campaign_id", rule.getCampaign() != null ? rule.getCampaign().getId() : ""),
                        Map.entry("owner_type", rule.getOwnerType().name()),
                        Map.entry("owner_id", rule.getOwnerId() != null ? rule.getOwnerId() : ""),
                        Map.entry("scope_type", rule.getScopeType().name()),
                        Map.entry("ref_id", rule.getRefId() != null ? rule.getRefId() : ""),
                        Map.entry("discount_type", rule.getDiscountType().name()),
                        Map.entry("discount_value", rule.getDiscountValue()),
                        Map.entry("max_discount_amount", rule.getMaxDiscountAmount() != null ? rule.getMaxDiscountAmount() : ""),
                        Map.entry("start_time", rule.getStartTime().toString()),
                        Map.entry("end_time", rule.getEndTime().toString())
                ));

        // Cache eviction cho markdown
        cacheEvictionPort.evictMarkdownCache();

        log.info("Created markdown rule: id={}, scope={}, ref={}",
                rule.getId(), rule.getScopeType(), rule.getRefId());
        return rule;
    }

    /**
     * Cập nhật Markdown Rule.
     * Theo thiết kế promotion_events.md - Section 2.2: MARKDOWN_UPDATED.
     */
    @Transactional
    public MarkdownRule updateMarkdownRule(Long id, MarkdownRule update) {
        MarkdownRule existing = markdownRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarkdownRule", id));

        existing.setDiscountType(update.getDiscountType());
        existing.setDiscountValue(update.getDiscountValue());
        existing.setMaxDiscountAmount(update.getMaxDiscountAmount());
        existing.setStartTime(update.getStartTime());
        existing.setEndTime(update.getEndTime());
        existing = markdownRuleRepository.save(existing);

        // Outbox Event: MARKDOWN_UPDATED
        outboxService.publishEvent(
                "MARKDOWN_RULE",
                existing.getId().toString(),
                "MARKDOWN_UPDATED",
                Map.of(
                        "rule_id", existing.getId(),
                        "changes", Map.of(
                                "discount_value", existing.getDiscountValue(),
                                "end_time", existing.getEndTime().toString()
                        )
                ));

        cacheEvictionPort.evictMarkdownCache();

        log.info("Updated markdown rule: id={}", id);
        return existing;
    }

    /**
     * Deactivate Markdown Rule (Admin tắt thủ công).
     * Theo thiết kế promotion_events.md - Section 2.3: MARKDOWN_ENDED.
     */
    @Transactional
    public void deactivateMarkdownRule(Long id) {
        MarkdownRule rule = markdownRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarkdownRule", id));

        rule.setStatus(MarkdownRuleStatus.INACTIVE);
        markdownRuleRepository.save(rule);

        // Outbox Event: MARKDOWN_ENDED
        outboxService.publishEvent(
                "MARKDOWN_RULE",
                rule.getId().toString(),
                "MARKDOWN_ENDED",
                Map.of(
                        "rule_id", rule.getId(),
                        "scope_type", rule.getScopeType().name(),
                        "ref_id", rule.getRefId() != null ? rule.getRefId() : "",
                        "reason", "ADMIN_DEACTIVATED"
                ));

        cacheEvictionPort.evictMarkdownCache();

        log.info("Deactivated markdown rule: id={}", id);
    }
}

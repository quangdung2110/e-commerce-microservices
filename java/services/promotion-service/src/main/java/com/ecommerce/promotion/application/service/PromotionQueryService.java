package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.exception.ResourceNotFoundException;
import com.ecommerce.promotion.domain.entity.*;
import com.ecommerce.promotion.domain.enums.PromotionStatus;
import com.ecommerce.promotion.domain.enums.WalletStatus;
import com.ecommerce.promotion.domain.enums.MarkdownRuleStatus;
import com.ecommerce.promotion.domain.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Service read-only cho Promotions, Wallets, Usages, Markdown Rules.
 * Theo thiết kế promotion_workflow.md - Luồng 3.
 */
@Service
@Transactional(readOnly = true)
public class PromotionQueryService {

    private final CampaignRepository campaignRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionBudgetRepository budgetRepository;
    private final UserCouponWalletRepository walletRepository;
    private final CouponUsageRepository usageRepository;
    private final MarkdownRuleRepository markdownRuleRepository;

    public PromotionQueryService(
            CampaignRepository campaignRepository,
            PromotionRepository promotionRepository,
            PromotionBudgetRepository budgetRepository,
            UserCouponWalletRepository walletRepository,
            CouponUsageRepository usageRepository,
            MarkdownRuleRepository markdownRuleRepository) {
        this.campaignRepository = campaignRepository;
        this.promotionRepository = promotionRepository;
        this.budgetRepository = budgetRepository;
        this.walletRepository = walletRepository;
        this.usageRepository = usageRepository;
        this.markdownRuleRepository = markdownRuleRepository;
    }

    // =========================================================================
    // Campaign Queries
    // =========================================================================

    public Campaign getCampaign(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
    }

    public List<Campaign> getAllCampaigns() {
        return campaignRepository.findAll();
    }

    // =========================================================================
    // Promotion Queries
    // =========================================================================

    public Promotion getPromotion(Long id) {
        return promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));
    }

    public Promotion getPromotionByCode(String code) {
        return promotionRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", code));
    }

    /**
     * Lấy promotions ACTIVE đang trong thời hạn.
     */
    public List<Promotion> getActivePromotions() {
        Instant now = Instant.now();
        return promotionRepository.findByStatusAndStartTimeBeforeAndEndTimeAfter(
                PromotionStatus.ACTIVE, now, now);
    }

    /**
     * Lấy eligible promotions cho giá trị giỏ hàng.
     * Theo thiết kế promotion_db_design.md - Section 1.2: "Gợi ý mã khuyến mãi"
     */
    public List<Promotion> getEligiblePromotions(BigDecimal cartTotal) {
        return promotionRepository.findEligiblePromotions(Instant.now(), cartTotal);
    }

    public PromotionBudget getPromotionBudget(Long promotionId) {
        return budgetRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("PromotionBudget", promotionId));
    }

    // =========================================================================
    // Wallet Queries
    // Theo thiết kế promotion_db_design.md - Section 1.5
    // =========================================================================

    /**
     * Lấy ví voucher của user.
     * Tối ưu bằng Composite Index (user_id, status).
     */
    public List<UserCouponWallet> getUserWallet(Long userId, WalletStatus status) {
        if (status != null) {
            return walletRepository.findByUserIdAndStatus(userId, status);
        }
        return walletRepository.findByUserId(userId);
    }

    // =========================================================================
    // Usage Queries (Sổ cái)
    // Theo thiết kế promotion_db_design.md - Section 1.6
    // =========================================================================

    /**
     * Lịch sử voucher của user.
     * API GET /users/{id}/coupon-history
     */
    public List<CouponUsage> getUserCouponHistory(Long userId) {
        return usageRepository.findByUserId(userId);
    }

    // =========================================================================
    // Markdown Rule Queries
    // Theo thiết kế promotion_db_design.md - Section 2.1
    // =========================================================================

    public MarkdownRule getMarkdownRule(Long id) {
        return markdownRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarkdownRule", id));
    }

    /**
     * Lấy tất cả active markdown rules — phục vụ Redis Cache & On-the-fly detail page.
     * Theo thiết kế redis_caching_design.md - Section 2.6
     */
    public List<MarkdownRule> getActiveMarkdownRules() {
        return markdownRuleRepository.findByStatus(MarkdownRuleStatus.ACTIVE);
    }
}

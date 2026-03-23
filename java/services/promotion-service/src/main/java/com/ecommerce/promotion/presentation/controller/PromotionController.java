package com.ecommerce.promotion.presentation.controller;

import com.ecommerce.promotion.application.service.PromotionCommandService;
import com.ecommerce.promotion.application.service.PromotionQueryService;
import com.ecommerce.promotion.domain.entity.Campaign;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.entity.PromotionBudget;
import com.ecommerce.promotion.domain.entity.UserCouponWallet;
import com.ecommerce.promotion.domain.entity.CouponUsage;
import com.ecommerce.promotion.domain.enums.*;
import com.ecommerce.promotion.presentation.dto.ActivateBudgetRequest;
import com.ecommerce.promotion.presentation.dto.CampaignRequest;
import com.ecommerce.promotion.presentation.dto.PromotionRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST Controller cho Admin CRUD Promotions & Campaigns.
 * Theo thiết kế promotion_workflow.md - Luồng 1: Admin Workflow.
 *
 * Endpoints:
 * - POST   /v1/campaigns               → Tạo campaign
 * - PUT    /v1/campaigns/{id}           → Cập nhật campaign
 * - GET    /v1/campaigns/{id}           → Lấy campaign
 * - GET    /v1/campaigns                → Danh sách campaigns
 *
 * - POST   /v1/promotions               → Tạo promotion
 * - PUT    /v1/promotions/{id}          → Cập nhật promotion
 * - GET    /v1/promotions/{id}          → Lấy promotion
 * - GET    /v1/promotions/active        → Danh sách promotions đang active
 * - GET    /v1/promotions/eligible      → Gợi ý mã cho giá trị giỏ hàng
 * - GET    /v1/promotions/code/{code}   → Tìm theo code
 * - GET    /v1/promotions/{id}/budget   → Xem budget
 * - POST   /v1/promotions/{id}/budget/activate → Kích hoạt budget lên Redis
 *
 * - GET    /v1/users/{userId}/wallet             → Ví voucher user
 * - GET    /v1/users/{userId}/coupon-history      → Lịch sử sử dụng coupon
 */
@RestController
@RequestMapping("/v1")
public class PromotionController {

    private static final Logger log = LoggerFactory.getLogger(PromotionController.class);

    private final PromotionCommandService commandService;
    private final PromotionQueryService queryService;

    public PromotionController(
            PromotionCommandService commandService,
            PromotionQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    // =========================================================================
    // Campaign Endpoints
    // Theo thiết kế promotion_db_design.md - Section 1.1
    // =========================================================================

    @PostMapping("/campaigns")
    public ResponseEntity<Campaign> createCampaign(@Valid @RequestBody CampaignRequest request) {
        Campaign campaign = new Campaign();
        campaign.setName(request.getName());
        campaign.setStartTime(request.getStartTime());
        campaign.setEndTime(request.getEndTime());
        Campaign created = commandService.createCampaign(campaign);
        log.info("API: Created campaign id={}", created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/campaigns/{id}")
    public ResponseEntity<Campaign> updateCampaign(
            @PathVariable Long id,
            @Valid @RequestBody CampaignRequest request) {
        Campaign update = new Campaign();
        update.setName(request.getName());
        update.setStartTime(request.getStartTime());
        update.setEndTime(request.getEndTime());
        if (request.getStatus() != null) {
            update.setStatus(CampaignStatus.valueOf(request.getStatus()));
        }
        Campaign updated = commandService.updateCampaign(id, update);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<Campaign> getCampaign(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getCampaign(id));
    }

    @GetMapping("/campaigns")
    public ResponseEntity<List<Campaign>> getAllCampaigns() {
        return ResponseEntity.ok(queryService.getAllCampaigns());
    }

    // =========================================================================
    // Promotion CRUD Endpoints
    // Theo thiết kế promotion_workflow.md - Luồng 1
    // =========================================================================

    @PostMapping("/promotions")
    public ResponseEntity<Promotion> createPromotion(@Valid @RequestBody PromotionRequest request) {
        Promotion promotion = mapToEntity(request);
        Promotion created = commandService.createPromotion(promotion);
        log.info("API: Created promotion id={}, code={}", created.getId(), created.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/promotions/{id}")
    public ResponseEntity<Promotion> updatePromotion(
            @PathVariable Long id,
            @Valid @RequestBody PromotionRequest request) {
        Promotion update = mapToEntity(request);
        if (request.getStatus() != null) {
            update.setStatus(PromotionStatus.valueOf(request.getStatus()));
        }
        Promotion updated = commandService.updatePromotion(id, update);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/promotions/{id}")
    public ResponseEntity<Promotion> getPromotion(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getPromotion(id));
    }

    @GetMapping("/promotions/code/{code}")
    public ResponseEntity<Promotion> getPromotionByCode(@PathVariable String code) {
        return ResponseEntity.ok(queryService.getPromotionByCode(code));
    }

    @GetMapping("/promotions/active")
    public ResponseEntity<List<Promotion>> getActivePromotions() {
        return ResponseEntity.ok(queryService.getActivePromotions());
    }

    /**
     * Gợi ý mã khuyến mãi cho giá trị giỏ hàng.
     * Theo thiết kế promotion_db_design.md - Section 1.2: truy vấn nhanh "Gợi ý mã".
     */
    @GetMapping("/promotions/eligible")
    public ResponseEntity<List<Promotion>> getEligiblePromotions(
            @RequestParam BigDecimal cartTotal) {
        return ResponseEntity.ok(queryService.getEligiblePromotions(cartTotal));
    }

    // =========================================================================
    // Budget Endpoints
    // Theo thiết kế promotion_db_design.md - Section 1.3
    // =========================================================================

    @GetMapping("/promotions/{id}/budget")
    public ResponseEntity<PromotionBudget> getPromotionBudget(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getPromotionBudget(id));
    }

    /**
     * Kích hoạt Budget lên Redis (Admin warm-up).
     * Theo thiết kế redis_caching_design.md - Section 2.2: Warm-up.
     */
    @PostMapping("/promotions/{id}/budget/activate")
    public ResponseEntity<Void> activateBudget(
            @PathVariable Long id,
            @Valid @RequestBody ActivateBudgetRequest request) {
        commandService.activateBudget(id, request.getTotalQuantity(), request.getTotalBudget());
        log.info("API: Activated budget for promotion id={}", id);
        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // User Wallet & History Endpoints
    // Theo thiết kế promotion_db_design.md - Section 1.5 & 1.6
    // =========================================================================

    @GetMapping("/users/{userId}/wallet")
    public ResponseEntity<List<UserCouponWallet>> getUserWallet(
            @PathVariable Long userId,
            @RequestParam(required = false) WalletStatus status) {
        return ResponseEntity.ok(queryService.getUserWallet(userId, status));
    }

    /**
     * Lịch sử voucher của user.
     * API GET /v1/users/{userId}/coupon-history
     * Theo thiết kế promotion_db_design.md - Section 1.6
     */
    @GetMapping("/users/{userId}/coupon-history")
    public ResponseEntity<List<CouponUsage>> getUserCouponHistory(@PathVariable Long userId) {
        return ResponseEntity.ok(queryService.getUserCouponHistory(userId));
    }

    // =========================================================================
    // Mapper helper
    // =========================================================================

    private Promotion mapToEntity(PromotionRequest request) {
        Promotion promo = new Promotion();
        promo.setOwnerType(OwnerType.valueOf(request.getOwnerType()));
        promo.setOwnerId(request.getOwnerId());
        promo.setCode(request.getCode());
        promo.setType(PromotionType.valueOf(request.getType()));
        promo.setDiscountType(DiscountType.valueOf(request.getDiscountType()));
        promo.setDiscountValue(request.getDiscountValue());
        promo.setMaxDiscountAmount(request.getMaxDiscountAmount());
        promo.setMinPurchaseAmount(request.getMinPurchaseAmount());
        promo.setConditionsRule(request.getConditionsRule());
        promo.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        promo.setIsExclusive(request.getIsExclusive() != null ? request.getIsExclusive() : false);
        promo.setStartTime(request.getStartTime());
        promo.setEndTime(request.getEndTime());
        return promo;
    }
}

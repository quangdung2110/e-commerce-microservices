package com.ecommerce.promotion.presentation.controller;

import com.ecommerce.promotion.application.dto.FlashSaleClaimResponse;
import com.ecommerce.promotion.application.service.CouponClaimService;
import com.ecommerce.promotion.infrastructure.kafka.PromotionKafkaProducer;
import com.ecommerce.promotion.presentation.dto.PreWarmFlashSaleRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller cho Flash Sale Coupon Claim.
 * Theo thiết kế promotion_workflow.md - Luồng 2: Tranh Mã Flash Sale (SPOP Claiming).
 *
 * Flow:
 * 1. Client POST /v1/flash-sales/{promotionId}/claim
 * 2. Rate Limit check → Redis Lua Script 1 (SPOP)
 * 3. Kafka produce COUPON_CLAIMED (trực tiếp, không qua Outbox)
 * 4. Return response ngay lập tức cho Client
 *
 * Endpoints:
 * - POST   /v1/flash-sales/{promotionId}/claim     → Giật mã Flash Sale
 * - POST   /v1/flash-sales/{promotionId}/pre-warm   → Admin pre-warm claim pool
 */
@RestController
@RequestMapping("/v1/flash-sales")
public class CouponController {

    private static final Logger log = LoggerFactory.getLogger(CouponController.class);

    private final CouponClaimService couponClaimService;
    private final PromotionKafkaProducer kafkaProducer;

    public CouponController(
            CouponClaimService couponClaimService,
            PromotionKafkaProducer kafkaProducer) {
        this.couponClaimService = couponClaimService;
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Giật mã Flash Sale.
     * Theo thiết kế promotion_workflow.md - Luồng 2:
     *   Bước 3: Redis Lua Script 1 (SPOP)
     *   Bước 4: Kafka produce COUPON_CLAIMED
     *   → Trả kết quả về cho Client "Thành công"
     *
     * Theo thiết kế promotion_outbox_pattern.md - Section 6:
     *   COUPON_CLAIMED → Kafka TRỰC TIẾP (KHÔNG qua Outbox)
     *   vì không ghi DB trước tại thời điểm claim.
     *
     * @param promotionId Flash Sale promotion ID
     * @param userId      User ID (từ Auth header hoặc request param)
     * @return FlashSaleClaimResponse
     */
    @PostMapping("/{promotionId}/claim")
    public ResponseEntity<FlashSaleClaimResponse> claimFlashSale(
            @PathVariable Long promotionId,
            @RequestHeader("X-User-Id") Long userId) {

        // Bước 2-3: Rate limit + SPOP Claiming
        FlashSaleClaimResponse response = couponClaimService.claimFlashSale(promotionId, userId);

        // Bước 4: Direct Kafka produce COUPON_CLAIMED
        // Theo thiết kế promotion_outbox_pattern.md - Section 6
        kafkaProducer.publishCouponClaimed(promotionId, response.getClaimId(), userId);

        log.info("API: Flash sale claim success: promo={}, user={}, claimId={}",
                promotionId, userId, response.getClaimId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Admin Pre-warm Flash Sale claim pool.
     * Theo thiết kế promotion_workflow.md - Luồng 2 Bước 1:
     *   Admin đăng Flash Sale → Batch insert coupon_claims → Push IDs vào Redis Set.
     *
     * @param promotionId Flash Sale promotion ID
     * @param request     PreWarmFlashSaleRequest (quantity)
     */
    @PostMapping("/{promotionId}/pre-warm")
    public ResponseEntity<Void> preWarmFlashSale(
            @PathVariable Long promotionId,
            @Valid @RequestBody PreWarmFlashSaleRequest request) {
        couponClaimService.preWarmFlashSale(promotionId, request.getQuantity());
        log.info("API: Pre-warmed flash sale pool: promo={}, qty={}", promotionId, request.getQuantity());
        return ResponseEntity.ok().build();
    }
}

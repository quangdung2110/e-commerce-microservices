package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.dto.FlashSaleClaimResponse;
import com.ecommerce.promotion.application.exception.*;
import com.ecommerce.promotion.application.port.RateLimitPort;
import com.ecommerce.promotion.application.port.RedisLuaScriptPort;
import com.ecommerce.promotion.domain.entity.CouponClaim;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.entity.UserCouponWallet;
import com.ecommerce.promotion.domain.enums.PromotionStatus;
import com.ecommerce.promotion.domain.enums.PromotionType;
import com.ecommerce.promotion.domain.enums.WalletStatus;
import com.ecommerce.promotion.domain.repository.CouponClaimRepository;
import com.ecommerce.promotion.domain.repository.PromotionRepository;
import com.ecommerce.promotion.domain.repository.UserCouponWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service xử lý Flash Sale SPOP Claiming.
 * Theo thiết kế promotion_workflow.md - Luồng 2: Tranh Mã Flash Sale.
 *
 * Flow:
 * 1. Rate limit check (5 req/10s)
 * 2. Redis Lua Script 1 (SPOP Claiming)
 * 3. Ném Event COUPON_CLAIMED vào Kafka (trực tiếp, không qua Outbox)
 * 4. Background Worker sẽ ghi DB async
 *
 * Theo thiết kế promotion_outbox_pattern.md - Section 6:
 *   User claim mã Flash Sale → Event được produce trực tiếp (KHÔNG qua Outbox)
 *   vì không ghi DB trước tại thời điểm claim.
 */
@Service
public class CouponClaimService {

    private static final Logger log = LoggerFactory.getLogger(CouponClaimService.class);

    private final PromotionRepository promotionRepository;
    private final CouponClaimRepository couponClaimRepository;
    private final UserCouponWalletRepository walletRepository;
    private final RedisLuaScriptPort redisLuaScriptPort;
    private final RateLimitPort rateLimitPort;

    public CouponClaimService(
            PromotionRepository promotionRepository,
            CouponClaimRepository couponClaimRepository,
            UserCouponWalletRepository walletRepository,
            RedisLuaScriptPort redisLuaScriptPort,
            RateLimitPort rateLimitPort) {
        this.promotionRepository = promotionRepository;
        this.couponClaimRepository = couponClaimRepository;
        this.walletRepository = walletRepository;
        this.redisLuaScriptPort = redisLuaScriptPort;
        this.rateLimitPort = rateLimitPort;
    }

    /**
     * Giật mã Flash Sale.
     * Theo thiết kế promotion_workflow.md - Luồng 2: SPOP Claiming.
     *
     * @param promotionId ID promotion Flash Sale
     * @param userId      ID user
     * @return FlashSaleClaimResponse
     */
    public FlashSaleClaimResponse claimFlashSale(Long promotionId, Long userId) {
        // Bước 0: Rate Limit — 5 req/10s
        // Theo thiết kế redis_caching_design.md - Section 2.5
        if (!rateLimitPort.allowRequest(userId)) {
            throw new RateLimitExceededException(userId);
        }

        // Validate promotion
        Promotion promo = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", promotionId));
        if (promo.getStatus() != PromotionStatus.ACTIVE) {
            throw new PromotionExpiredException(promotionId);
        }
        if (promo.getType() != PromotionType.LIMITED_FLASH) {
            throw new CoreBusinessException(
                    "Promotion " + promotionId + " is not a flash sale", "NOT_FLASH_SALE");
        }
        Instant now = Instant.now();
        if (promo.getStartTime().isAfter(now) || promo.getEndTime().isBefore(now)) {
            throw new PromotionExpiredException(promotionId);
        }

        // Bước 3: Redis Lua Script 1 — SPOP Claiming
        // Theo thiết kế redis_caching_design.md - Script 1
        long result = redisLuaScriptPort.spopClaim(promotionId, userId);

        if (result == -1) {
            // User đã claim rồi
            throw new DuplicateClaimException(promotionId, userId);
        }
        if (result == -2) {
            // Sold out
            throw new FlashSaleSoldOutException(promotionId);
        }

        // result = claimId (thành công)
        long claimId = result;
        log.info("Flash sale claim success: promo={}, user={}, claimId={}", promotionId, userId, claimId);

        // Bước 4: Return response — Event COUPON_CLAIMED sẽ được produce bởi caller/infrastructure
        // Theo thiết kế promotion_workflow.md - Luồng 2 Bước 4:
        //   Backend ném Event Async COUPON_CLAIMED vào Kafka. Trả kết quả về cho Client.
        return new FlashSaleClaimResponse(promotionId, claimId, userId, true, "Claim success");
    }

    /**
     * Pre-warming: Insert coupon_claims vào DB và push vào Redis Set.
     * Theo thiết kế promotion_workflow.md - Luồng 2 Bước 1:
     *   Admin đăng 1.000 mã Flash Sale. Hệ thống Insert 1.000 dòng AVAILABLE.
     *   Push 1.000 ID vào Redis Set: promo:{promotion_id}:claim_pool.
     *
     * @param promotionId ID promotion
     * @param quantity    Số lượng vé cần tạo
     */
    @Transactional
    public void preWarmFlashSale(Long promotionId, int quantity) {
        Promotion promo = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", promotionId));

        // Batch insert coupon_claims with status=AVAILABLE, owner_id=NULL
        // Theo thiết kế promotion_db_design.md - Section 1.4
        List<Long> claimIds = new java.util.ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            CouponClaim claim = new CouponClaim();
            claim.setPromotionId(promotionId);
            // owner_id = null (chưa ai giành)
            // status default = AVAILABLE
            claim = couponClaimRepository.save(claim);
            claimIds.add(claim.getId());
        }

        // Push tất cả ID vào Redis Set
        // Theo thiết kế redis_caching_design.md - Section 2.3: Pre-warming
        redisLuaScriptPort.warmUpClaimPool(promotionId, claimIds);
        log.info("Pre-warmed flash sale pool: promo={}, claims={}", promotionId, claimIds.size());
    }

    /**
     * Worker callback: Ghi DB sau khi nhận Kafka event COUPON_CLAIMED.
     * Theo thiết kế promotion_workflow.md - Luồng 2 Bước 5:
     *   Worker đọc Event, UPDATE coupon_claims SET owner_id, status=CLAIMED.
     *   HDEL pending_claims.
     */
    @Transactional
    public void processClaimEvent(Long claimId, Long userId, Long promotionId) {
        // UPDATE coupon_claims SET owner_id = userId, status = 'CLAIMED' WHERE id = claimId
        couponClaimRepository.claimCoupon(claimId, userId);

        // INSERT/UPDATE user_coupon_wallets
        UserCouponWallet wallet = walletRepository
                .findByUserIdAndPromotionId(userId, promotionId)
                .orElseGet(() -> {
                    UserCouponWallet w = new UserCouponWallet();
                    w.setUserId(userId);
                    w.setPromotionId(promotionId);
                    return w;
                });
        wallet.setStatus(WalletStatus.SAVED);
        wallet.setSavedAt(Instant.now());
        walletRepository.save(wallet);

        // HDEL promo:{promotion_id}:pending_claims claimId
        redisLuaScriptPort.removePendingClaim(promotionId, claimId);
        log.info("Processed claim event: claimId={}, userId={}, promoId={}", claimId, userId, promotionId);
    }

    /**
     * Fault Tolerance Job: Quét pending claims bị kẹt > 5 phút.
     * Theo thiết kế redis_caching_design.md - Script 1: Fault Tolerance Job.
     * Theo thiết kế promotion_workers.md - Section 2.2.
     *
     * @return danh sách claim entries cần retry (để Infrastructure layer produce Kafka)
     */
    public List<Map.Entry<Long, Long>> sweepPendingClaims(Long promotionId) {
        // HGETALL promo:{promo_id}:pending_claims
        Map<Long, String> pendingClaims = redisLuaScriptPort.getPendingClaims(promotionId);
        List<Map.Entry<Long, Long>> retryList = new java.util.ArrayList<>();

        long now = Instant.now().getEpochSecond();

        for (Map.Entry<Long, String> entry : pendingClaims.entrySet()) {
            Long claimId = entry.getKey();
            String value = entry.getValue(); // "userId:timestamp"
            String[] parts = value.split(":");
            if (parts.length < 2) continue;

            long userId = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);

            // Nếu > 5 phút
            if (now - timestamp > 300) {
                // Query DB: SELECT status FROM coupon_claims WHERE id = claimId
                CouponClaim claim = couponClaimRepository.findById(claimId).orElse(null);
                if (claim != null && claim.getStatus() == com.ecommerce.promotion.domain.enums.CouponClaimStatus.AVAILABLE) {
                    // Trường hợp 1 (Sập APP thật): status vẫn AVAILABLE → nhồi lại Event
                    retryList.add(Map.entry(claimId, userId));
                    log.warn("Pending claim stuck: claimId={}, userId={}, re-queuing", claimId, userId);
                } else {
                    // Trường hợp 2: DB đã CLAIMED nhưng lệnh HDEL chưa chạy → dọn rác
                    redisLuaScriptPort.removePendingClaim(promotionId, claimId);
                    log.info("Cleaned stale pending claim: claimId={}", claimId);
                }
            }
        }

        return retryList;
    }
}

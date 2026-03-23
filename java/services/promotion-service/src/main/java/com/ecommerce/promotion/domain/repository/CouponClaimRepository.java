package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.CouponClaim;
import com.ecommerce.promotion.domain.enums.CouponClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository cho CouponClaim (Flash Sale SPOP).
 * Theo thiết kế promotion_db_design.md - Section 1.4
 */
@Repository
public interface CouponClaimRepository extends JpaRepository<CouponClaim, Long> {

    /**
     * Lấy tất cả claim IDs (status = AVAILABLE) cho 1 promotion.
     * Dùng cho Pre-warming Job: push vào Redis Set.
     * Theo thiết kế redis_caching_design.md - Section 2.3
     */
    @Query("SELECT cc.id FROM CouponClaim cc WHERE cc.promotionId = :promoId AND cc.status = 'AVAILABLE'")
    List<Long> findAvailableClaimIds(@Param("promoId") Long promotionId);

    /**
     * Update claim: gán owner và status=CLAIMED.
     * Theo thiết kế promotion_db_design.md - Section 1.4: Thiết kế Hiện tại
     * Worker nhận claim_id từ Redis SPOP, chỉ chạy UPDATE đơn giản.
     */
    @Modifying
    @Query("UPDATE CouponClaim cc SET cc.ownerId = :userId, cc.status = 'CLAIMED' WHERE cc.id = :claimId")
    int claimCoupon(@Param("claimId") Long claimId, @Param("userId") Long userId);

    /**
     * Chunked Batch Deletion cho Coupon Claims Cleanup Job.
     * Theo thiết kế coupon_claims_deletion_strategy.md - Section 3
     */
    @Modifying
    @Query(value = "DELETE FROM coupon_claims WHERE id IN " +
           "(SELECT id FROM coupon_claims WHERE promotion_id = :promoId LIMIT :batchSize)",
           nativeQuery = true)
    int deleteByPromotionIdInBatch(@Param("promoId") Long promotionId, @Param("batchSize") int batchSize);

    /**
     * Kiểm tra status của 1 claim — dùng cho Pending Claims Sweeper.
     * Theo thiết kế redis_caching_design.md - Script 1: Fault Tolerance Job
     */
    CouponClaim findByIdAndStatus(Long id, CouponClaimStatus status);
}

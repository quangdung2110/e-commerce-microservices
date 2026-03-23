package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.enums.PromotionStatus;
import com.ecommerce.promotion.domain.enums.PromotionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho Promotion (Cart Vouchers).
 * Theo thiết kế promotion_db_design.md - Section 1.2
 */
@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    Optional<Promotion> findByCode(String code);

    List<Promotion> findByStatusAndStartTimeBeforeAndEndTimeAfter(
        PromotionStatus status, Instant now1, Instant now2);

    /**
     * Lấy tất cả promotions ACTIVE và đang trong thời hạn.
     * Dùng cho Cache Warmer lúc khởi động.
     * Theo thiết kế redis_caching_design.md - Section 2.2: Startup Job
     */
    @Query("SELECT p FROM Promotion p WHERE p.status = 'ACTIVE' AND p.endTime > :now")
    List<Promotion> findAllActivePromotions(@Param("now") Instant now);

    /**
     * Lấy promotions có min_purchase_amount phù hợp.
     * Theo thiết kế promotion_db_design.md - Section 1.2: "Gợi ý mã khuyến mãi"
     */
    @Query("SELECT p FROM Promotion p WHERE p.status = 'ACTIVE' " +
           "AND p.startTime <= :now AND p.endTime > :now " +
           "AND (p.minPurchaseAmount IS NULL OR p.minPurchaseAmount <= :cartTotal) " +
           "ORDER BY p.priority DESC")
    List<Promotion> findEligiblePromotions(
        @Param("now") Instant now,
        @Param("cartTotal") BigDecimal cartTotal);

    /**
     * Lấy promotions đã hết hạn quá 30 ngày — dùng cho Coupon Claims Cleanup Job.
     * Theo thiết kế coupon_claims_deletion_strategy.md - Section 3
     */
    @Query("SELECT p.id FROM Promotion p WHERE p.endTime < :cutoff")
    List<Long> findExpiredPromotionIds(@Param("cutoff") Instant cutoff);
}

package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.CouponUsage;
import com.ecommerce.promotion.domain.enums.UsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho CouponUsage (Sổ cái Phân tán - Ledger).
 * Theo thiết kế promotion_db_design.md - Section 1.6
 */
@Repository
public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    /**
     * Tìm usage theo order + promotion.
     * Theo thiết kế: Unique Constraint (order_id, promotion_id)
     */
    Optional<CouponUsage> findByOrderIdAndPromotionId(Long orderId, Long promotionId);

    /**
     * Lịch sử voucher của user.
     * Theo thiết kế: Index (user_id) cho GET /users/{id}/coupon-history
     */
    List<CouponUsage> findByUserId(Long userId);

    /**
     * Tìm các reservation đã quá hạn 15 phút — dùng cho Reservation TTL Sweep Job.
     * Theo thiết kế redis_caching_design.md - Section 2.4: Background Job Sweep
     * Theo thiết kế promotion_workers.md - Section 2.3
     */
    @Query("SELECT cu FROM CouponUsage cu WHERE cu.status = 'RESERVED' AND cu.reservedAt < :cutoff")
    List<CouponUsage> findExpiredReservations(@Param("cutoff") Instant cutoff);
}

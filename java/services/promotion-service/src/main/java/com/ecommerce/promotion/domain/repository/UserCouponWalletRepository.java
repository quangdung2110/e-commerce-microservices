package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.UserCouponWallet;
import com.ecommerce.promotion.domain.enums.WalletStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository cho UserCouponWallet (Ví Voucher).
 * Theo thiết kế promotion_db_design.md - Section 1.5
 */
@Repository
public interface UserCouponWalletRepository extends JpaRepository<UserCouponWallet, Long> {

    /**
     * Tìm entry wallet theo user và promotion.
     * Theo thiết kế: Unique Constraint (user_id, promotion_id)
     */
    Optional<UserCouponWallet> findByUserIdAndPromotionId(Long userId, Long promotionId);

    /**
     * Lấy danh sách mã SAVED đang khả dụng trong ví user.
     * Theo thiết kế: Composite Index (user_id, status) tối ưu cho API GET /wallet?status=SAVED
     */
    List<UserCouponWallet> findByUserIdAndStatus(Long userId, WalletStatus status);

    /**
     * Lấy tất cả wallets của 1 user.
     */
    List<UserCouponWallet> findByUserId(Long userId);
}

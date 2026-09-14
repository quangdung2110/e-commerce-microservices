package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCouponWalletRepository extends JpaRepository<UserCouponWalletEntity, Long> {

    Optional<UserCouponWalletEntity> findByUserIdAndPromotionId(UUID userId, long promotionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserCouponWalletEntity w where w.userId = :userId and w.promotionId = :promotionId")
    Optional<UserCouponWalletEntity> lockByUserAndPromotion(@Param("userId") UUID userId,
                                                            @Param("promotionId") long promotionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserCouponWalletEntity w where w.id = :id")
    Optional<UserCouponWalletEntity> lockById(@Param("id") long id);

    List<UserCouponWalletEntity> findByUserIdAndStatus(UUID userId, WalletStatus status);

    @Query("select w from UserCouponWalletEntity w where w.promotionId = :promotionId and w.usedOrderId = :orderId")
    Optional<UserCouponWalletEntity> findUsedByOrder(@Param("promotionId") long promotionId,
                                                     @Param("orderId") long orderId);
}

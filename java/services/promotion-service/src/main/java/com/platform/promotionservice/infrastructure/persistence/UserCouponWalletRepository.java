package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCouponWalletRepository extends JpaRepository<UserCouponWalletEntity, Long> {

    interface WalletReadRow {
        UserCouponWalletEntity getWallet();
        PromotionEntity getPromotion();
    }

    /** Bounded owner-scoped read. Effective expiry is projected, never written by GET. */
    @Query("""
            select w as wallet, p as promotion from UserCouponWalletEntity w
            join PromotionEntity p on p.id = w.promotionId
            where w.userId = :userId and w.id < :beforeId
              and (:status is null
                or (:status = 'SAVED' and w.status = 'SAVED' and w.expiredAt > :now)
                or (:status = 'USED' and w.status = 'USED')
                or (:status = 'EXPIRED' and (w.status = 'EXPIRED'
                    or (w.status = 'SAVED' and w.expiredAt <= :now))))
            order by w.id desc
            """)
    List<WalletReadRow> readWallet(@Param("userId") UUID userId, @Param("status") String status,
                                  @Param("now") Instant now, @Param("beforeId") long beforeId,
                                  Pageable pageable);

    Optional<UserCouponWalletEntity> findByUserIdAndPromotionId(UUID userId, long promotionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserCouponWalletEntity w where w.userId = :userId and w.promotionId = :promotionId")
    Optional<UserCouponWalletEntity> lockByUserAndPromotion(@Param("userId") UUID userId,
                                                            @Param("promotionId") long promotionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserCouponWalletEntity w where w.id = :id")
    Optional<UserCouponWalletEntity> lockById(@Param("id") long id);

    List<UserCouponWalletEntity> findByUserIdAndStatus(UUID userId, WalletStatus status);

    interface SavedCandidate {
        Long getId();
        Long getPromotionId();
        Instant getExpiredAt();
    }

    /** Projection keeps wallet discovery bounded too; status is always SAVED. */
    @Query("""
            select w.id as id, w.promotionId as promotionId, w.expiredAt as expiredAt
            from UserCouponWalletEntity w
            where w.userId = :userId and w.status = 'SAVED' and w.id > :afterId
            order by w.id asc
            """)
    List<SavedCandidate> findSavedAfter(@Param("userId") UUID userId, @Param("afterId") long afterId,
                                        Pageable pageable);

    @Query("select w from UserCouponWalletEntity w where w.promotionId = :promotionId and w.usedOrderId = :orderId")
    Optional<UserCouponWalletEntity> findUsedByOrder(@Param("promotionId") long promotionId,
                                                     @Param("orderId") long orderId);
}

package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.CouponClaimEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponClaimRepository extends JpaRepository<CouponClaimEntity, Long> {

    @Query("select c.id from CouponClaimEntity c where c.promotionId = :promotionId and c.status = 'AVAILABLE' and c.id > :afterId order by c.id asc")
    List<Long> availableIdsAfter(@Param("promotionId") long promotionId, @Param("afterId") long afterId,
                                 Pageable pageable);

    long countByPromotionId(long promotionId);

    long countByPromotionIdAndOwnerIdIsNotNull(long promotionId);

    Optional<CouponClaimEntity> findByPromotionIdAndOwnerId(long promotionId, UUID ownerId);
}

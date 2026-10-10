package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.service.PromotionView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PromotionRepository extends JpaRepository<PromotionEntity, Long> {

    boolean existsByCode(String code);

    List<PromotionEntity> findByCampaignIdOrderByIdAsc(long campaignId);

    @Query("select p from PromotionEntity p where p.campaignId = :campaignId and p.id > :afterId order by p.id asc")
    List<PromotionEntity> pageAfter(@Param("campaignId") long campaignId, @Param("afterId") long afterId,
                                    Pageable pageable);

    /** Scalar keyset page: ownership exclusion happens before LIMIT; no managed entities retained. */
    @Query("""
            select p.id from PromotionEntity p, CampaignEntity c
            where p.campaignId = c.id and p.enabled = true
              and p.id > :afterId
              and c.publicationStatus = 'PUBLISHED'
              and c.startTime <= :now and c.endTime > :now
              and p.distributionMode in ('PUBLIC_SAVE', 'LIMITED_CLAIM')
              and not exists (select w.id from UserCouponWalletEntity w
                              where w.userId = :userId and w.promotionId = p.id)
            order by p.id asc
            """)
    List<Long> findPublicActiveAfter(@Param("userId") UUID userId, @Param("now") Instant now,
                                     @Param("afterId") long afterId, Pageable pageable);

    /** Cache-miss batch, including campaign fields, without loading managed entities. */
    @Query("""
            select new com.platform.promotionservice.service.PromotionView(
                p.id, c.id, c.revision, p.name, p.description, c.publicationStatus,
                c.startTime, c.endTime, p.code, p.distributionMode, p.discountType,
                p.discountValue, p.maxDiscountVnd, p.minOrderAmountVnd, p.conditionsRule,
                p.ruleSchemaVersion, p.validityMode, p.validForSeconds, p.redeemUntil,
                p.enabled, cast(:cacheVersion as long))
            from PromotionEntity p, CampaignEntity c
            where p.campaignId = c.id and p.id in :ids
            """)
    List<PromotionView> findSnapshotViews(@Param("ids") List<Long> ids, @Param("cacheVersion") long cacheVersion);

    /** Mọi promotion thuộc campaign không còn DRAFT: runtime rebuild / sweeper. */
    @Query("""
            select p.id from PromotionEntity p, CampaignEntity c
            where p.campaignId = c.id and c.publicationStatus <> 'DRAFT' and p.id > :afterId
            order by p.id asc
            """)
    List<Long> runtimeIdsAfter(@Param("afterId") long afterId, Pageable pageable);
}

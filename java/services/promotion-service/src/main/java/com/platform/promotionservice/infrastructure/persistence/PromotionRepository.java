package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PromotionRepository extends JpaRepository<PromotionEntity, Long> {

    boolean existsByCode(String code);

    List<PromotionEntity> findByCampaignIdOrderByIdAsc(long campaignId);

    @Query("select p from PromotionEntity p where p.campaignId = :campaignId and p.id > :afterId order by p.id asc")
    List<PromotionEntity> pageAfter(@Param("campaignId") long campaignId, @Param("afterId") long afterId,
                                    Pageable pageable);

    /** Public discovery: promotion enabled thuộc campaign PUBLISHED trong [start,end). */
    @Query("""
            select p from PromotionEntity p, CampaignEntity c
            where p.campaignId = c.id and p.enabled = true
              and c.publicationStatus = 'PUBLISHED'
              and c.startTime <= :now and c.endTime > :now
              and p.distributionMode in ('PUBLIC_SAVE', 'LIMITED_CLAIM')
            order by p.id asc
            """)
    List<PromotionEntity> findPublicActive(@Param("now") Instant now, Pageable pageable);

    /** Mọi promotion thuộc campaign không còn DRAFT: runtime rebuild / sweeper. */
    @Query("""
            select p.id from PromotionEntity p, CampaignEntity c
            where p.campaignId = c.id and c.publicationStatus <> 'DRAFT' and p.id > :afterId
            order by p.id asc
            """)
    List<Long> runtimeIdsAfter(@Param("afterId") long afterId, Pageable pageable);
}

package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
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

public interface MarkdownRuleRepository extends JpaRepository<MarkdownRuleEntity, Long> {

    List<MarkdownRuleEntity> findByCampaignIdOrderByIdAsc(long campaignId);

    @Query("select m from MarkdownRuleEntity m where m.campaignId = :campaignId and m.id > :afterId order by m.id asc")
    List<MarkdownRuleEntity> pageAfter(@Param("campaignId") long campaignId, @Param("afterId") long afterId,
                                       Pageable pageable);

    Optional<MarkdownRuleEntity> findByCampaignIdAndProductId(long campaignId, UUID productId);

    /** Rule chưa invalidated của product, lock theo ID tăng dần (WF-PROMO-009 bước 2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MarkdownRuleEntity m where m.productId = :productId and m.invalidatedAt is null order by m.id asc")
    List<MarkdownRuleEntity> lockActiveByProduct(@Param("productId") UUID productId);

    /** Ứng viên winner: enabled, chưa invalidated, campaign PUBLISHED và ACTIVE tại now. */
    @Query("""
            select m from MarkdownRuleEntity m, CampaignEntity c
            where m.campaignId = c.id and m.productId = :productId
              and m.enabled = true and m.invalidatedAt is null
              and c.publicationStatus = 'PUBLISHED'
              and c.startTime <= :now and c.endTime > :now
            order by m.priority desc, m.id asc
            """)
    List<MarkdownRuleEntity> findWinnerCandidates(@Param("productId") UUID productId, @Param("now") Instant now);
}

package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<CampaignEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CampaignEntity c where c.id = :id")
    Optional<CampaignEntity> lockById(@Param("id") long id);

    boolean existsByCode(String code);

    @Query("""
            select c from CampaignEntity c
            where (:status is null or c.publicationStatus = :status)
              and c.id > :afterId
            order by c.id asc
            """)
    List<CampaignEntity> pageAfter(@Param("status") PublicationStatus status,
                                   @Param("afterId") long afterId, Pageable pageable);

    /** Campaign PUBLISHED còn boundary chưa qua lookback: rebuild/fallback schedule. */
    @Query("select c from CampaignEntity c where c.publicationStatus = 'PUBLISHED' and c.endTime >= :since")
    List<CampaignEntity> publishedEndingAfter(@Param("since") Instant since);
}

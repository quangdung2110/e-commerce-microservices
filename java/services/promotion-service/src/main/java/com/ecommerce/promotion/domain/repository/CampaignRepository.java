package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.Campaign;
import com.ecommerce.promotion.domain.enums.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository cho Campaign.
 * Theo thiết kế promotion_db_design.md - Section 1.1
 */
@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByStatus(CampaignStatus status);

    List<Campaign> findByStatusAndEndTimeAfter(CampaignStatus status, Instant now);
}

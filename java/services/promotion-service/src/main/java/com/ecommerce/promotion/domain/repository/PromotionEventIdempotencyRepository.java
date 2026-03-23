package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.PromotionEventIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository cho PromotionEventIdempotency (Chống lặp Message).
 * Theo thiết kế promotion_db_design.md - Section 1.7
 */
@Repository
public interface PromotionEventIdempotencyRepository extends JpaRepository<PromotionEventIdempotency, String> {

    boolean existsByEventId(String eventId);

    /**
     * Dọn rác idempotency keys quá 7 ngày.
     * Theo thiết kế promotion_workers.md - Section 3.2
     */
    @Modifying
    @Query("DELETE FROM PromotionEventIdempotency pie WHERE pie.processedAt < :cutoff")
    int deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}

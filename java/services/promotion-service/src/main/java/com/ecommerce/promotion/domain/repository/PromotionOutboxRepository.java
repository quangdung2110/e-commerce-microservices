package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.PromotionOutbox;
import com.ecommerce.promotion.domain.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository cho PromotionOutbox (Outbox Pattern).
 * Theo thiết kế promotion_db_design.md - Section 1.8
 * Theo thiết kế promotion_outbox_pattern.md - Section 4
 */
@Repository
public interface PromotionOutboxRepository extends JpaRepository<PromotionOutbox, UUID> {

    /**
     * Poll pending/failed events cho Outbox Relay Worker.
     * Theo thiết kế promotion_outbox_pattern.md - Section 4.1:
     *   SELECT * FROM promotion_outbox
     *   WHERE status IN ('PENDING', 'FAILED')
     *   AND (next_retry_at IS NULL OR next_retry_at <= NOW())
     *   ORDER BY created_at ASC LIMIT 100
     */
    @Query("SELECT po FROM PromotionOutbox po WHERE po.status IN ('PENDING', 'FAILED') " +
           "AND (po.nextRetryAt IS NULL OR po.nextRetryAt <= :now) " +
           "ORDER BY po.createdAt ASC")
    List<PromotionOutbox> findPendingEvents(@Param("now") Instant now,
                                             org.springframework.data.domain.Pageable pageable);

    /**
     * Dọn rác Outbox — xóa PUBLISHED + DEAD quá 7 ngày.
     * Theo thiết kế promotion_outbox_pattern.md - Section 4.4
     * Theo thiết kế promotion_workers.md - Section 3.3
     */
    @Modifying
    @Query("DELETE FROM PromotionOutbox po WHERE po.status IN ('PUBLISHED', 'DEAD') " +
           "AND po.createdAt < :cutoff")
    int deletePublishedAndDeadBefore(@Param("cutoff") Instant cutoff);

    /**
     * Đếm DEAD events cho Dead Letter Handler monitoring.
     * Theo thiết kế promotion_workers.md - Section 3.5
     */
    @Query("SELECT po FROM PromotionOutbox po WHERE po.status = 'DEAD'")
    List<PromotionOutbox> findDeadEvents();
}

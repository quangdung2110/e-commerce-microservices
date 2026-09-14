package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionCommandReceiptEntity;
import com.platform.promotionservice.domain.enums.CommandOperation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommandReceiptRepository extends JpaRepository<PromotionCommandReceiptEntity, UUID> {

    Optional<PromotionCommandReceiptEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PromotionCommandReceiptEntity> findByOperationTypeAndPromotionIdAndOrderId(
            CommandOperation operationType, long promotionId, long orderId);

    /** Receipt chưa hội tụ Redis quá ngưỡng — partial index redis_finalized_at IS NULL. */
    @Query("select r from PromotionCommandReceiptEntity r where r.redisFinalizedAt is null and r.completedAt < :before order by r.completedAt asc")
    List<PromotionCommandReceiptEntity> findUnfinalizedBefore(@Param("before") Instant before, Pageable pageable);
}

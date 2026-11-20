package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionOutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<PromotionOutboxEventEntity, UUID> {

    Optional<PromotionOutboxEventEntity> findByDedupKey(String dedupKey);

    /** Các dedup key đã có trong outbox (một query theo unique index); dùng để lọc trước khi lock/tính winner. */
    @Query("select o.dedupKey from PromotionOutboxEventEntity o where o.dedupKey in :keys")
    List<String> findExistingDedupKeys(@Param("keys") Collection<String> keys);
}

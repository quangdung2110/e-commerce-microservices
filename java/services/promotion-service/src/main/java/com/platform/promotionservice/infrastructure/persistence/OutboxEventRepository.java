package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionOutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<PromotionOutboxEventEntity, UUID> {

    Optional<PromotionOutboxEventEntity> findByDedupKey(String dedupKey);
}

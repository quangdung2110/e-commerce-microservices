package com.platform.inventoryservice.infrastructure.persistence.jpa.spring;

import com.platform.inventoryservice.domain.enums.OutboxEventType;
import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.OutboxJpaCustom;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.OutboxJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxJpaRepository
        extends JpaRepository<OutboxJpaEntity, UUID>,
                OutboxJpaCustom {

    List<OutboxJpaEntity> findByAggregateIdAndRefundRequestIdAndEventType(
        String aggregateId, String refundRequestId, OutboxEventType eventType);

    long countByCreatedAtBefore(Instant watermark);
}

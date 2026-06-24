package com.platform.inventoryservice.infrastructure.persistence.outbound.repository;

import com.platform.inventoryservice.domain.entity.OutboxEvent;
import com.platform.inventoryservice.domain.enums.OutboxEventType;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.OutboxJpaRepository;
import com.platform.inventoryservice.infrastructure.persistence.outbound.mapper.OutboxMapper;
import com.platform.inventoryservice.port.outbound.database.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

    private final OutboxJpaRepository jpa;
    private final OutboxMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OutboxEvent event) {
        jpa.save(mapper.toJpa(event));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findReturnReceivedAll(String orderId, String refundRequestId) {
        return jpa.findByAggregateIdAndRefundRequestIdAndEventType(
            orderId, refundRequestId, OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL
        ).stream().findFirst().map(mapper::toDomain);
    }

    @Override
    @Transactional
    public int deleteBatchOlderThan(Instant watermark, int limit) {
        return jpa.deleteBatchOlderThan(watermark, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public long countOlderThan(Instant watermark) {
        return jpa.countByCreatedAtBefore(watermark);
    }
}

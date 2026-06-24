package com.platform.inventoryservice.port.outbound.database;

import com.platform.inventoryservice.domain.entity.OutboxEvent;

import java.time.Instant;
import java.util.Optional;

public interface OutboxRepository {
    void insert(OutboxEvent event);
    Optional<OutboxEvent> findReturnReceivedAll(String orderId, String refundRequestId);
    int deleteBatchOlderThan(Instant watermark, int limit);
    long countOlderThan(Instant watermark);
}

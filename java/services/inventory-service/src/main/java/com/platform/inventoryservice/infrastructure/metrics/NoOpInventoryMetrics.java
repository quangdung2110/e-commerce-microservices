package com.platform.inventoryservice.infrastructure.metrics;

import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import org.springframework.stereotype.Component;

@Component
public class NoOpInventoryMetrics implements InventoryMetrics {
    @Override
    public void recordLuaInvocation(String operationTag, String status) {
        // No-op dummy implementation for M15
    }

    @Override
    public void recordLuaDuration(String operationTag, long durationNanos) {
        // No-op dummy implementation for M15
    }

    @Override
    public void recordRelayError(String streamKey, String errorType) {}

    @Override
    public void recordKafkaPublishError(String eventType, String errorType) {}

    @Override
    public void recordKafkaPublish(String eventType) {}

    @Override
    public void recordMutationProcessed(String changeType) {}

    @Override
    public void recordReserveSuccess(int itemCount) {}

    @Override
    public void recordReserveFailure(String errorType) {}

    @Override
    public void recordDeductSuccess(int itemCount) {}

    @Override
    public void recordRollbackSuccess(int itemCount, String changeType) {}

    @Override
    public void recordRestock(String referenceType, int quantity) {}

    @Override
    public void recordShip(int affectedItems) {}

    @Override
    public void recordRefundExpectation(int markedCount) {}

    @Override
    public void recordUnlock() {}

    @Override
    public void recordOutboxCleanup(int deletedCount, long durationMs) {}

    @Override
    public void recordOutboxPending(long pendingCount) {}
}

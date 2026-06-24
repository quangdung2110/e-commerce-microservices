package com.platform.inventoryservice.port.outbound.metrics;

public interface InventoryMetrics {
    void recordLuaInvocation(String operationTag, String status);
    void recordLuaDuration(String operationTag, long durationNanos);
    void recordRelayError(String streamKey, String errorType);
    void recordKafkaPublishError(String eventType, String errorType);
    void recordKafkaPublish(String eventType);
    void recordMutationProcessed(String changeType);
    
    // Temporal Activity Metrics
    void recordReserveSuccess(int itemCount);
    void recordReserveFailure(String errorType);
    void recordDeductSuccess(int itemCount);
    void recordRollbackSuccess(int itemCount, String changeType);

    // Admin Activity Metrics
    void recordRestock(String referenceType, int quantity);
    void recordShip(int affectedItems);
    void recordRefundExpectation(int markedCount);
    void recordUnlock();

    // Outbox Cleanup Metrics
    void recordOutboxCleanup(int deletedCount, long durationMs);
    void recordOutboxPending(long pendingCount);
}

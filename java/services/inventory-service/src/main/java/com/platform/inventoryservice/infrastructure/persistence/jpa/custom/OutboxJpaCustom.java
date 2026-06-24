package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import java.time.Instant;

public interface OutboxJpaCustom {
    /** Batch delete với LIMIT — tránh lock bảng. */
    int deleteBatchOlderThan(Instant watermark, int limit);

    /** Check duplicate cho RETURN_RECEIVED_ALL trước khi insert (race-safe double check). */
    boolean existsReturnReceivedAll(String orderId, String refundRequestId);
}

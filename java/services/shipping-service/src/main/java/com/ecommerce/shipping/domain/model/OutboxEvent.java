package com.ecommerce.shipping.domain.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// Theo thiết kế shipping_db_design.md mục 3.2: transactional outbox domain model.
public record OutboxEvent(
        UUID id,
        String aggregateType,
        Long aggregateId,
        String eventType,
        Map<String, Object> payload,
        String status,
        Integer retryCount,
        Integer maxRetries,
        OffsetDateTime publishedAt,
        OffsetDateTime nextRetryAt,
        OffsetDateTime createdAt
) {
}

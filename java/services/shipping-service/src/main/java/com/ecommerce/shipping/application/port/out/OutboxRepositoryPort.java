package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.OutboxEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Theo thiết kế shipping_db_design.md mục 3.2: transactional outbox.
public interface OutboxRepositoryPort {

	OutboxEvent save(OutboxEvent outboxEvent);

	List<OutboxEvent> pollPending(int limit);

	void markPublished(UUID eventId, OffsetDateTime publishedAt);

	void markFailed(UUID eventId, int retryCount, OffsetDateTime nextRetryAt);

	void markDead(UUID eventId, int retryCount);
}

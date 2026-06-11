package com.platform.productservice.worker.consumer.handler;

import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.event.ProductStatusChangedEvent;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.infrastructure.persistence.entity.ProductJpaEntity;
import com.platform.productservice.infrastructure.persistence.jpa.spring.ProductJpaRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.worker.common.EventIdempotencyGuard;
import com.platform.productservice.worker.common.StalenessChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles CATEGORY_DELETED events from Kafka.
 * Batch-updates all active products belonging to the deleted category to HIDDEN status.
 * Also inserts PRODUCT_STATUS_CHANGED events into the outbox for downstream consumers.
 *
 * <p>This handler only writes to the database, not to Redis.
 * Cache invalidation is handled by SKU_UPDATED events emitted from the batch update.</p>
 */
@Component
public class CategoryDeletedHandler {

    private static final Logger log = LoggerFactory.getLogger(CategoryDeletedHandler.class);

    private static final int BATCH_SIZE = 100;

    private final ProductJpaRepository productJpaRepository;
    private final OutboxRepository outboxRepository;
    private final EventIdempotencyGuard idempotencyGuard;
    private final StalenessChecker stalenessChecker;

    public CategoryDeletedHandler(
            ProductJpaRepository productJpaRepository,
            OutboxRepository outboxRepository,
            EventIdempotencyGuard idempotencyGuard,
            StalenessChecker stalenessChecker) {
        this.productJpaRepository = productJpaRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.stalenessChecker = stalenessChecker;
    }

    @Transactional
    public void handle(CloudEvent event) {
        String eventId = event.getEventId();
        log.info("CategoryDeletedHandler: processing eventId={}", eventId);

        if (!idempotencyGuard.isNew(eventId)) {
            log.info("CategoryDeletedHandler: duplicate event skipped, eventId={}", eventId);
            return;
        }

        Long eventTimestamp = parseTimestamp(event.getTimestamp());

        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            log.warn("CategoryDeletedHandler: null payload, eventId={}", eventId);
            return;
        }

        String categoryId = String.valueOf(payload.get("categoryId"));
        if (categoryId == null || categoryId.isBlank()) {
            log.warn("CategoryDeletedHandler: missing categoryId in payload, eventId={}", eventId);
            return;
        }

        log.info("CategoryDeletedHandler: processing categoryId={}, eventId={}", categoryId, eventId);

        List<ProductJpaEntity> activeProducts = productJpaRepository
                .findByCategoryIdAndStatus(categoryId, "ACTIVE");

        if (activeProducts.isEmpty()) {
            log.info("CategoryDeletedHandler: no active products found for categoryId={}", categoryId);
            return;
        }

        log.info("CategoryDeletedHandler: found {} active products for categoryId={}",
                activeProducts.size(), categoryId);

        List<ProductJpaEntity> toHide = new ArrayList<>();
        List<OutboxEvent> outboxEvents = new ArrayList<>();
        int totalHidden = 0;

        for (ProductJpaEntity product : activeProducts) {
            long productUpdatedAt = product.getUpdatedAt() != null
                    ? product.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                    : 0L;

            if (!stalenessChecker.isProductEventFresh(eventTimestamp, productUpdatedAt)) {
                log.debug("CategoryDeletedHandler: product {} is newer than event, skipping", product.getProductId());
                continue;
            }

            toHide.add(product);

            if (toHide.size() >= BATCH_SIZE) {
                int hidden = hideBatch(toHide);
                totalHidden += hidden;
                outboxEvents.addAll(createOutboxEvents(toHide));
                toHide.clear();
            }
        }

        if (!toHide.isEmpty()) {
            int hidden = hideBatch(toHide);
            totalHidden += hidden;
            outboxEvents.addAll(createOutboxEvents(toHide));
        }

        if (!outboxEvents.isEmpty()) {
            outboxRepository.saveAll(outboxEvents);
            log.info("CategoryDeletedHandler: saved {} outbox events for categoryId={}",
                    outboxEvents.size(), categoryId);
        }

        log.info("CategoryDeletedHandler: completed, categoryId={}, totalHidden={}, outboxEventsCreated={}",
                categoryId, totalHidden, outboxEvents.size());
    }

    private int hideBatch(List<ProductJpaEntity> products) {
        List<String> productIds = products.stream()
                .map(ProductJpaEntity::getProductId)
                .toList();

        int updated = productJpaRepository.hideProductsByIds(productIds);
        log.debug("CategoryDeletedHandler: batch hide updated {} products", updated);
        return updated;
    }

    private List<OutboxEvent> createOutboxEvents(List<ProductJpaEntity> products) {
        List<OutboxEvent> events = new ArrayList<>();
        for (ProductJpaEntity product : products) {
            ProductStatusChangedEvent domainEvent = new ProductStatusChangedEvent(
                    product.getProductId(),
                    product.getStatus().name(),
                    "HIDDEN",
                    "event-handler-worker",
                    "SYSTEM",
                    "category_deleted",
                    List.of()
            );
            String payload = String.format(
                    "{\"productId\":\"%s\",\"previousStatus\":\"%s\",\"newStatus\":\"HIDDEN\"," +
                            "\"reason\":\"category_deleted\",\"changedBy\":\"event-handler-worker\",\"changedByType\":\"SYSTEM\"}",
                    product.getProductId(), product.getStatus().name()
            );
            OutboxEvent outboxEvent = OutboxEvent.of(
                    domainEvent,
                    product.getProductId(),
                    product.getProductId(),
                    payload
            );
            events.add(outboxEvent);
        }
        return events;
    }

    private Long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            try {
                return Long.parseLong(timestamp);
            } catch (Exception ex) {
                log.warn("CategoryDeletedHandler: could not parse timestamp '{}', defaulting to 0", timestamp);
                return 0L;
            }
        }
    }
}

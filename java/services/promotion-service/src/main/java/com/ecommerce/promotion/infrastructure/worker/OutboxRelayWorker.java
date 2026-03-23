package com.ecommerce.promotion.infrastructure.worker;

import com.ecommerce.promotion.domain.entity.PromotionOutbox;
import com.ecommerce.promotion.domain.enums.OutboxStatus;
import com.ecommerce.promotion.domain.repository.PromotionOutboxRepository;
import com.ecommerce.promotion.infrastructure.config.KafkaTopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox Relay Worker — poll promotion_outbox và publish lên Kafka.
 * Theo thiết kế promotion_outbox_pattern.md - Section 4.1.
 *
 * H6 Fix Pattern (theo product-service):
 * 1. fetchAndLock() — short @Transactional: SELECT FOR UPDATE SKIP LOCKED → commit ngay
 * 2. publishToKafka() — pure Kafka I/O, KHÔNG giữ DB connection
 * 3. markPublished() / markFailed() — @Transactional(REQUIRES_NEW) riêng biệt
 *
 * Topic Mapping (Theo thiết kế promotion_events.md - Section 4):
 * - MARKDOWN_* → t.promotion.markdown.events (partition key: aggregate_id = rule_id)
 * - COUPON_*   → t.promotion.coupon.events   (partition key: aggregate_id = promotion_id)
 * - BUDGET_*   → t.promotion.budget.events   (partition key: aggregate_id = order_id)
 *
 * Exponential Backoff: 5s × 3^(retry_count - 1) → 5s, 15s, 45s, 135s, 405s
 * Theo thiết kế promotion_outbox_pattern.md - Section 4.2.
 */
@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    private static final long KAFKA_SEND_TIMEOUT_MS = 5_000;

    private final PromotionOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${promotion.outbox.batch-size:100}")
    private int batchSize;

    @Value("${promotion.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxRelayWorker(PromotionOutboxRepository outboxRepository,
                             KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll cycle — chạy mỗi 500ms.
     * Theo thiết kế promotion_outbox_pattern.md - Section 4.1: polling interval 500ms.
     */
    @Scheduled(fixedDelayString = "${promotion.outbox.polling-interval-ms:500}")
    public void pollAndPublish() {
        List<PromotionOutbox> events = fetchAndLock();
        if (events.isEmpty()) {
            return;
        }
        log.debug("OutboxRelay: processing {} events", events.size());
        for (PromotionOutbox event : events) {
            publishEvent(event);
        }
    }

    /**
     * Phase 1: Fetch + Lock rows → commit ngay → giải phóng DB connection.
     * Theo product-service H6 fix: Kafka round-trip NGOÀI transaction.
     */
    @Transactional
    public List<PromotionOutbox> fetchAndLock() {
        Instant now = Instant.now();
        List<PromotionOutbox> events = outboxRepository.findPendingEvents(
                now, PageRequest.of(0, batchSize));

        if (!events.isEmpty()) {
            // Claim rows: đẩy next_retry_at về tương lai → instance khác không pick lại
            Instant claimUntil = now.plusSeconds(60);
            events.forEach(event -> event.setNextRetryAt(claimUntil));
            outboxRepository.saveAll(events);
        }
        return events;
    }

    /**
     * Phase 2: Publish event lên Kafka (NGOÀI DB transaction).
     * Theo thiết kế promotion_events.md - Section 4: Topic Mapping.
     */
    private void publishEvent(PromotionOutbox event) {
        try {
            String topic = resolveTopicByEventType(event.getEventType());
            String partitionKey = event.getAggregateId();

            Message<String> message = MessageBuilder.withPayload(event.getPayload())
                    .setHeader(KafkaHeaders.TOPIC, topic)
                    .setHeader(KafkaHeaders.KEY, partitionKey)
                    .setHeader("event_type", event.getEventType())
                    .setHeader("event_id", event.getEventId().toString())
                    .setHeader("aggregate_type", event.getAggregateType())
                    .build();

            // Blocking send with timeout — đảm bảo Kafka nhận được
            kafkaTemplate.send(message).get(KAFKA_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Phase 3a: Mark PUBLISHED
            markPublished(event.getEventId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Kafka send interrupted for event {}", event.getEventId());
            markFailed(event.getEventId(), event.getRetryCount(), "Interrupted: " + e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("Failed to publish outbox event {}: {}", event.getEventId(), e.getMessage());
            markFailed(event.getEventId(), event.getRetryCount(), e.getMessage());
        }
    }

    /**
     * Phase 3a: Mark event as PUBLISHED.
     * @Transactional(REQUIRES_NEW) — isolated DB write, không ảnh hưởng batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        outboxRepository.findById(eventId).ifPresent(ev -> {
            ev.setStatus(OutboxStatus.PUBLISHED);
            ev.setErrorMessage(null);
            outboxRepository.save(ev);
        });
    }

    /**
     * Phase 3b: Mark event as FAILED hoặc DEAD.
     * Exponential Backoff: 5s × 3^(retry_count - 1).
     * Theo thiết kế promotion_outbox_pattern.md - Section 4.2.
     *
     * retry_count: 1 → 5s, 2 → 15s, 3 → 45s, 4 → 135s, 5 → DEAD
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, int currentRetryCount, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(ev -> {
            int newRetryCount = currentRetryCount + 1;
            ev.setRetryCount(newRetryCount);
            ev.setErrorMessage(errorMessage);

            if (newRetryCount >= maxRetries) {
                ev.setStatus(OutboxStatus.DEAD);
                log.warn("Outbox event {} marked as DEAD after {} retries", eventId, newRetryCount);
            } else {
                ev.setStatus(OutboxStatus.FAILED);
                // Exponential backoff: 5s × 3^(retry_count - 1)
                long backoffSeconds = (long) (5 * Math.pow(3, newRetryCount - 1));
                ev.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                log.info("Outbox event {} failed, retry #{} in {}s", eventId, newRetryCount, backoffSeconds);
            }

            outboxRepository.save(ev);
        });
    }

    /**
     * Topic Mapping — event_type prefix → Kafka topic.
     * Theo thiết kế promotion_events.md - Section 4: Topic Mapping Table.
     *
     * MARKDOWN_* → t.promotion.markdown.events
     * COUPON_*   → t.promotion.coupon.events
     * BUDGET_*   → t.promotion.budget.events (fallback)
     */
    private String resolveTopicByEventType(String eventType) {
        if (eventType == null) {
            return KafkaTopicConfig.PROMOTION_BUDGET_EVENTS_TOPIC;
        }
        if (eventType.startsWith("MARKDOWN_")) {
            return KafkaTopicConfig.PROMOTION_MARKDOWN_EVENTS_TOPIC;
        }
        if (eventType.startsWith("COUPON_")) {
            return KafkaTopicConfig.PROMOTION_COUPON_EVENTS_TOPIC;
        }
        // BUDGET_RESERVED, BUDGET_COMMITTED, etc. → budget topic
        return KafkaTopicConfig.PROMOTION_BUDGET_EVENTS_TOPIC;
    }
}

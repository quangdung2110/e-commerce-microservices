package com.ecommerce.search.worker;

import com.ecommerce.search.common.EventType;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Long-running Kafka consumer that polls batches from three upstream topics
 * ({@code t.product.events}, {@code t.category.events}, {@code t.review.events})
 * and dispatches each event to {@link EventHandler}.
 *
 * <h3>Batch processing contract</h3>
 * <ul>
 *   <li>Poll size is capped by {@code max.poll.records} (default 500).</li>
 *   <li>Events with an unknown or disallowed {@code event_type} are silently
 *       ignored and counted for observability.</li>
 *   <li>The offset is committed <em>after</em> the entire batch has been
 *       processed (manual ACK).</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * Individual record errors are caught and logged so a single bad record does
 * not stall the whole batch. Transient infrastructure errors (ES timeout,
 * cluster down) propagate to the container-level error handler which applies
 * the exponential-backoff retry policy defined in {@link KafkaConsumerConfig}.
 *
 * @see KafkaConsumerConfig
 * @see EventHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexingConsumer {

    private final EventHandler eventHandler;

    /**
     * Batch listener for all three upstream topics.
     *
     * <p>Topics are resolved via SpEL from {@code application.yml}:
     * <ul>
     *   <li>{@code search.topics.product} — {@code t.product.events}</li>
     *   <li>{@code search.topics.category} — {@code t.category.events}</li>
     *   <li>{@code search.topics.review} — {@code t.review.events}</li>
     * </ul>
     */
    @KafkaListener(
        topics = {
            "${search.topics.product}",
            "${search.topics.category}",
            "${search.topics.review}"
        },
        containerFactory = "kafkaListenerContainerFactory",
        groupId = KafkaConsumerConfig.CONSUMER_GROUP_ID
    )
    public void onBatch(List<ConsumerRecord<String, CloudEventEnvelope>> records, Acknowledgment ack) {
        log.debug("Received batch of {} records", records.size());

        int successCount = 0;
        int errorCount = 0;
        int ignoredCount = 0;

        for (ConsumerRecord<String, CloudEventEnvelope> record : records) {
            try {
                CloudEventEnvelope envelope = record.value();

                // === 1. Null guard ===
                if (envelope == null) {
                    log.warn("Null envelope at offset={}, skipping", record.offset());
                    ignoredCount++;
                    continue;
                }

                // === 2. Event type allowlist validation ===
                String eventTypeStr = envelope.eventType();
                if (eventTypeStr == null || eventTypeStr.isBlank()) {
                    log.warn("Empty event_type at offset={}, skipping", record.offset());
                    // TODO: increment search_event_ignored_total{topic, eventType=""} metric
                    ignoredCount++;
                    continue;
                }

                EventType eventType = EventType.fromString(eventTypeStr);
                if (eventType == null) {
                    log.warn("Unknown event_type '{}' at offset={}, ignoring. topic={}",
                        eventTypeStr, record.offset(), record.topic());
                    // TODO: increment search_event_ignored_total{topic, eventType} metric
                    ignoredCount++;
                    continue;
                }

                if (!eventType.isAllowedInConsumerGroup()) {
                    log.debug("Event type '{}' is not allowed in consumer group, ignoring. offset={}",
                        eventTypeStr, record.offset());
                    // TODO: increment search_event_ignored_total{topic, eventType} metric
                    ignoredCount++;
                    continue;
                }

                // === 3. Dispatch to EventHandler (Module 08) ===
                eventHandler.handle(envelope);
                successCount++;

            } catch (Exception e) {
                /*
                 * Individual record error — log and continue.
                 *
                 * TODO (Module 08): Distinguish between transient and non-recoverable.
                 *   - Transient (ES timeout / 429 / 503 / network): re-throw so the
                 *     container-level error handler applies exponential-backoff retry.
                 *   - Non-recoverable (document_missing_exception, mapping conflict):
                 *     keep the catch-and-continue pattern below; increment a dedicated
                 *     metric (search.document.missing.total, search.payload.invalid.total).
                 */
                log.error("Failed to process record at offset={}, partition={}, topic={}",
                    record.offset(), record.partition(), record.topic(), e);
                errorCount++;
            }
        }

        // === 4. Manual ACK after the full batch ===
        ack.acknowledge();

        if (log.isDebugEnabled() || errorCount > 0 || ignoredCount > 0) {
            log.info("Batch processed: total={}, success={}, error={}, ignored={}",
                records.size(), successCount, errorCount, ignoredCount);
        }
    }
}

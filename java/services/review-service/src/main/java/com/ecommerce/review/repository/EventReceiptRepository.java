package com.ecommerce.review.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * {@code review_event_receipts}: what turns at-least-once delivery into an
 * at-most-once effect.
 *
 * <p>Every consumer inserts its receipt in the same transaction as its effect. A
 * conflict means the event already had that effect, so the transaction commits as a
 * no-op and the record is acknowledged (REVIEW-INV-008).
 *
 * <p>Receipts must be retained for longer than Kafka retention plus a replay margin;
 * deleting them earlier would let a redrive apply an event twice.
 */
@Repository
public class EventReceiptRepository {

    /** The three consumer names that appear in {@code consumer_name}. */
    public static final String ORDER_SYNC = "order-sync";
    public static final String PRIVACY_SYNC = "privacy-sync";
    public static final String RATING_AGGREGATOR = "review-rating-aggregator";

    private final JdbcClient jdbc;

    public EventReceiptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when this call recorded the receipt, false when the event was already
     *         processed and the caller must produce no further effect
     */
    public boolean claim(String consumerName, UUID eventId, String topic, int partition, long offset) {
        return jdbc.sql("""
                INSERT INTO review_event_receipts
                    (consumer_name, event_id, source_topic, source_partition, source_offset)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """)
                .param(consumerName)
                .param(eventId)
                .param(topic)
                .param(partition)
                .param(offset)
                .update() == 1;
    }

    public boolean exists(String consumerName, UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM review_event_receipts WHERE consumer_name = ? AND event_id = ?")
                .param(consumerName).param(eventId).query(Long.class).single() > 0;
    }

    /**
     * Drops receipts older than the configured retention.
     *
     * <p>The cutoff must sit outside the maximum replay horizon; the caller computes it
     * from {@code REVIEW_EVENT_RECEIPT_RETENTION_DAYS}, which operations require to
     * exceed Kafka retention plus a safety margin.
     */
    public int deleteProcessedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM review_event_receipts WHERE processed_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}

package com.ecommerce.order.store;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Append-only multi-destination outbox, notification ledger and delivery receipts (ADR-016). */
@Repository
public class OutboxStore {

    public record LedgerRow(long orderId, String dedupKey, long sequence, UUID eventId, String payloadSha256) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID eventId, String aggregateId, String messageClass, String topic, String eventType,
                       String partitionKey, String dedupKey, String payloadJson) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_id, message_class, destination_topic, event_type,
                    partition_key, dedup_key, payload)
                VALUES (:id, :aggregate, :class, :topic, :type, :key, :dedup, CAST(:payload AS jsonb))
                """, new MapSqlParameterSource().addValue("id", eventId).addValue("aggregate", aggregateId)
                .addValue("class", messageClass).addValue("topic", topic).addValue("type", eventType)
                .addValue("key", partitionKey).addValue("dedup", dedupKey).addValue("payload", payloadJson));
    }

    public Optional<LedgerRow> ledger(long orderId, String dedupKey) {
        return jdbc.query("""
                SELECT order_id, dedup_key, notification_sequence, event_id, payload_sha256
                FROM order_notification_commands WHERE order_id = :order AND dedup_key = :dedup
                """, Map.of("order", orderId, "dedup", dedupKey), (rs, i) -> new LedgerRow(rs.getLong(1),
                rs.getString(2), rs.getLong(3), Db.uuid(rs, "event_id"), rs.getString(5))).stream().findFirst();
    }

    public void insertLedger(long orderId, String dedupKey, long sequence, UUID eventId, String templateId,
                             String payloadJson, String payloadSha256) {
        jdbc.update("""
                INSERT INTO order_notification_commands (order_id, dedup_key, notification_sequence, event_id,
                    template_id, payload, payload_sha256)
                VALUES (:order, :dedup, :sequence, :event, :template, CAST(:payload AS jsonb), :sha)
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("dedup", dedupKey)
                .addValue("sequence", sequence).addValue("event", eventId).addValue("template", templateId)
                .addValue("payload", payloadJson).addValue("sha", payloadSha256));
    }

    /** Idempotent receipt insert by the observer (exact event/topic). */
    public void recordReceipt(UUID eventId, String topic, int partition, long offset) {
        jdbc.update("""
                INSERT INTO outbox_delivery_receipts (event_id, destination_topic, kafka_partition, kafka_offset)
                VALUES (:event, :topic, :partition, :offset)
                ON CONFLICT (event_id, destination_topic) DO NOTHING
                """, new MapSqlParameterSource().addValue("event", eventId).addValue("topic", topic)
                .addValue("partition", partition).addValue("offset", offset));
    }

    /** Deletes only rows older than retention with an exact receipt past the safety delay. */
    public int trim(Duration retention, Duration safetyDelay, int batch) {
        return jdbc.update("""
                DELETE FROM outbox_events WHERE event_id IN (
                    SELECT o.event_id FROM outbox_events o
                    JOIN outbox_delivery_receipts r
                      ON r.event_id = o.event_id AND r.destination_topic = o.destination_topic
                    WHERE o.created_at < NOW() - CAST(:retention AS interval)
                      AND r.observed_at < NOW() - CAST(:safety AS interval)
                    LIMIT :batch)
                """, new MapSqlParameterSource().addValue("retention", retention.toSeconds() + " seconds")
                .addValue("safety", safetyDelay.toSeconds() + " seconds").addValue("batch", batch));
    }

    public long countWithoutReceipt(Duration olderThan) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o
                WHERE o.created_at < NOW() - CAST(:age AS interval)
                  AND NOT EXISTS (SELECT 1 FROM outbox_delivery_receipts r
                                  WHERE r.event_id = o.event_id AND r.destination_topic = o.destination_topic)
                """, Map.of("age", olderThan.toSeconds() + " seconds"), Long.class);
        return count == null ? 0 : count;
    }
}

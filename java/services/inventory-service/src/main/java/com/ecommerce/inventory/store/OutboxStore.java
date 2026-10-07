package com.ecommerce.inventory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox and the commit-ordered export table (ADR-022). A business or
 * projector transaction writes an unsequenced {@code outbox_event}; only the fenced
 * sequencer moves it to {@code published_event}, the single relay and change-feed source.
 */
@Repository
public class OutboxStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record OutboxEvent(long outboxId, UUID eventId, String topic, String messageKey, String eventType,
                              String skuId, ObjectNode payload) {
    }

    public void append(UUID eventId, String topic, String messageKey, String eventType, String skuId,
                       ObjectNode payload, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_event (event_id, topic, message_key, event_type, sku_id, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, eventId, topic, messageKey, eventType, skuId, json(payload), Timestamp.from(now));
    }

    /** Committed, unsequenced rows in {@code outbox_id} order, locked for this sequencer batch. */
    public List<OutboxEvent> lockUnsequenced(int limit) {
        return jdbc.query("""
                SELECT outbox_id, event_id, topic, message_key, event_type, sku_id, payload::text AS payload
                FROM outbox_event WHERE sequence IS NULL ORDER BY outbox_id LIMIT ? FOR UPDATE
                """, (rs, i) -> new OutboxEvent(rs.getLong("outbox_id"), rs.getObject("event_id", UUID.class),
                rs.getString("topic"), rs.getString("message_key"), rs.getString("event_type"),
                rs.getString("sku_id"), (ObjectNode) parse(rs.getString("payload"))), limit);
    }

    public long nextSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('export_sequence')", Long.class);
        return value == null ? 0 : value;
    }

    public void publish(OutboxEvent event, long sequence, ObjectNode finalized, Instant now) {
        jdbc.update("""
                INSERT INTO published_event (sequence, event_id, topic, message_key, event_type, sku_id, payload,
                                             created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, sequence, event.eventId(), event.topic(), event.messageKey(), event.eventType(), event.skuId(),
                json(finalized), Timestamp.from(now));
        jdbc.update("UPDATE outbox_event SET sequence = ?, sequenced_at = ? WHERE outbox_id = ?", sequence,
                Timestamp.from(now), event.outboxId());
    }

    public long unsequencedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE sequence IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    public Instant oldestUnsequencedAt() {
        Timestamp t = jdbc.queryForObject("SELECT min(created_at) FROM outbox_event WHERE sequence IS NULL",
                Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    /** Greatest assigned (and committed) export sequence; 0 before the first fact. */
    public long latestSequence() {
        Long n = jdbc.queryForObject("SELECT coalesce(max(sequence), 0) FROM published_event", Long.class);
        return n == null ? 0 : n;
    }

    public long retentionFloor() {
        Long n = jdbc.queryForObject("SELECT retention_floor FROM export_retention WHERE id = 1", Long.class);
        return n == null ? 0 : n;
    }

    /** Ordered availability facts after a sequence; {@code limit + 1} rows reveal a next page. */
    public List<JsonNode> availabilityChanges(String eventType, long afterSequence, int limit) {
        return jdbc.query("SELECT payload::text AS payload FROM published_event WHERE event_type = ? AND sequence > ? "
                + "ORDER BY sequence LIMIT ?", (rs, i) -> parse(rs.getString("payload")), eventType, afterSequence,
                limit);
    }

    /**
     * Removes published facts older than the retention window and raises the retention
     * floor in the same transaction, so a cursor below the floor is answered 410.
     */
    public int purgePublishedBefore(Instant cutoff) {
        Long floor = jdbc.queryForObject("SELECT max(sequence) FROM published_event WHERE created_at < ?", Long.class,
                Timestamp.from(cutoff));
        if (floor == null) {
            return 0;
        }
        jdbc.update("UPDATE export_retention SET retention_floor = GREATEST(retention_floor, ?) WHERE id = 1", floor);
        jdbc.update("DELETE FROM outbox_event WHERE sequence IS NOT NULL AND sequence <= ?", floor);
        return jdbc.update("DELETE FROM published_event WHERE sequence <= ?", floor);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

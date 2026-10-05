package com.ecommerce.order.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable inbox for Payment/Inventory/Delivery facts (INV-ORD-018/029, data.md §3.9). */
@Repository
public class InboxStore {

    public enum InsertResult { INSERTED, DUPLICATE_EVENT, SEMANTIC_DUPLICATE, SEMANTIC_COLLISION }

    public record InboxRow(String source, String eventId, String eventType, Long orderId, String streamId,
                           long sourceRevision, String payloadSha256, String payloadJson, String status) {
    }

    private static final RowMapper<InboxRow> ROW = (rs, i) -> new InboxRow(rs.getString("source"),
            rs.getString("event_id"), rs.getString("event_type"), Db.nullableLong(rs, "order_id"),
            rs.getString("stream_id"), rs.getLong("source_revision"), rs.getString("payload_sha256"),
            rs.getString("payload"), rs.getString("status"));

    private final NamedParameterJdbcTemplate jdbc;

    public InboxStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a received fact. A redelivered {@code (source,event_id)} is a duplicate; the
     * same stream revision under another ID is a semantic duplicate when the payload digest
     * matches and a collision (stored REJECTED for audit) otherwise.
     */
    public InsertResult insert(String source, String eventId, String eventType, Long orderId, String streamId,
                               long revision, String sha256, String payloadJson, String status) {
        if (find(source, eventId).isPresent()) {
            return InsertResult.DUPLICATE_EVENT;
        }
        Optional<InboxRow> semantic = jdbc.query("""
                SELECT * FROM order_consumed_events
                WHERE source = :source AND event_type = :type AND stream_id = :stream AND source_revision = :revision
                  AND status <> 'REJECTED'
                """, Map.of("source", source, "type", eventType, "stream", streamId, "revision", revision), ROW)
                .stream().findFirst();
        if (semantic.isPresent()) {
            if (semantic.get().payloadSha256().equals(sha256)) {
                return InsertResult.SEMANTIC_DUPLICATE;
            }
            insertRow(source, eventId, eventType, orderId, streamId, revision, sha256, payloadJson, "REJECTED",
                    "SEMANTIC_COLLISION");
            return InsertResult.SEMANTIC_COLLISION;
        }
        // ON CONFLICT keeps the surrounding transaction usable when a concurrent consumer won.
        return insertRow(source, eventId, eventType, orderId, streamId, revision, sha256, payloadJson, status, null)
                ? InsertResult.INSERTED : InsertResult.DUPLICATE_EVENT;
    }

    private boolean insertRow(String source, String eventId, String eventType, Long orderId, String streamId,
                           long revision, String sha256, String payloadJson, String status, String reason) {
        return jdbc.update("""
                INSERT INTO order_consumed_events (source, event_id, event_type, order_id, stream_id, source_revision,
                    payload_sha256, payload, status, reject_reason)
                VALUES (:source, :event, :type, :order, :stream, :revision, :sha, CAST(:payload AS jsonb), :status,
                    :reason)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("source", source).addValue("event", eventId)
                .addValue("type", eventType).addValue("order", orderId).addValue("stream", streamId)
                .addValue("revision", revision).addValue("sha", sha256).addValue("payload", payloadJson)
                .addValue("status", status).addValue("reason", reason)) == 1;
    }

    public Optional<InboxRow> find(String source, String eventId) {
        return jdbc.query("SELECT * FROM order_consumed_events WHERE source = :source AND event_id = :event",
                Map.of("source", source, "event", eventId), ROW).stream().findFirst();
    }

    public void setStatus(String source, String eventId, String status, String reason) {
        jdbc.update("""
                UPDATE order_consumed_events SET status = :status, reject_reason = :reason, updated_at = NOW()
                WHERE source = :source AND event_id = :event
                """, new MapSqlParameterSource().addValue("source", source).addValue("event", eventId)
                .addValue("status", status).addValue("reason", reason));
    }

    /** Parked rows for one order in arrival order, locked for replay. */
    public List<InboxRow> lockParked(long orderId, String status) {
        return jdbc.query("""
                SELECT * FROM order_consumed_events WHERE order_id = :order AND status = :status
                ORDER BY received_at FOR UPDATE SKIP LOCKED
                """, Map.of("order", orderId, "status", status), ROW);
    }

    /** Orders with parked work older than the given age, for the convergence reconciler. */
    public List<Long> ordersWithParked(String status, int limit) {
        return jdbc.query("""
                SELECT DISTINCT order_id FROM order_consumed_events
                WHERE status = :status AND order_id IS NOT NULL AND received_at < NOW() - INTERVAL '30 seconds'
                LIMIT :limit
                """, Map.of("status", status, "limit", limit), (rs, i) -> rs.getLong(1));
    }
}

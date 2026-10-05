package com.ecommerce.order.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DB-to-Temporal outboxes (ADR-015): {@code order_workflow_dispatches} for start/attach
 * and {@code order_workflow_messages} for SignalWithStart. Rows are inserted in the same
 * transaction as the business acceptance and claimed with {@code FOR UPDATE SKIP LOCKED}.
 */
@Repository
public class WorkflowOutboxStore {

    public record DispatchRow(UUID dispatchId, Long orderId, String workflowId, String workflowType,
                              String taskQueue, String inputJson, String status, int attempts) {
    }

    public record MessageRow(String messageId, Long orderId, String targetWorkflowId, String workflowType,
                             String taskQueue, String startInputJson, String messageType, String payloadJson,
                             String status, int attempts, Instant createdAt) {
    }

    private static final RowMapper<DispatchRow> DISPATCH = (rs, i) -> new DispatchRow(Db.uuid(rs, "dispatch_id"),
            Db.nullableLong(rs, "order_id"), rs.getString("workflow_id"), rs.getString("workflow_type"),
            rs.getString("task_queue"), rs.getString("input"), rs.getString("status"), rs.getInt("attempts"));

    private static final RowMapper<MessageRow> MESSAGE = (rs, i) -> new MessageRow(rs.getString("message_id"),
            Db.nullableLong(rs, "order_id"), rs.getString("target_workflow_id"), rs.getString("workflow_type"),
            rs.getString("task_queue"), rs.getString("start_input"), rs.getString("message_type"),
            rs.getString("payload"), rs.getString("status"), rs.getInt("attempts"), Db.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowOutboxStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a start request unless a live one already exists for the workflow ID. */
    public boolean insertDispatch(UUID dispatchId, Long orderId, String workflowId, String workflowType,
                                  String taskQueue, String inputJson, String inputDigest) {
        return jdbc.update("""
                INSERT INTO order_workflow_dispatches (dispatch_id, order_id, workflow_id, workflow_type, task_queue,
                    input, input_digest, status)
                VALUES (:id, :order, :workflow, :type, :queue, CAST(:input AS jsonb), :digest, 'PENDING')
                ON CONFLICT (workflow_id) WHERE status IN ('PENDING', 'IN_FLIGHT', 'RETRY') DO NOTHING
                """, new MapSqlParameterSource().addValue("id", dispatchId).addValue("order", orderId)
                .addValue("workflow", workflowId).addValue("type", workflowType).addValue("queue", taskQueue)
                .addValue("input", inputJson).addValue("digest", inputDigest)) == 1;
    }

    /** Inserts a message; the stable message ID makes redelivery of the same fact a no-op. */
    public boolean insertMessage(String messageId, Long orderId, String targetWorkflowId, String workflowType,
                                 String taskQueue, String startInputJson, String messageType, String payloadJson,
                                 String payloadDigest) {
        return jdbc.update("""
                INSERT INTO order_workflow_messages (message_id, order_id, target_workflow_id, workflow_type,
                    task_queue, start_input, message_type, payload, payload_digest, status)
                VALUES (:id, :order, :target, :type, :queue, CAST(:start AS jsonb), :messageType,
                    CAST(:payload AS jsonb), :digest, 'PENDING')
                ON CONFLICT (message_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("id", messageId).addValue("order", orderId)
                .addValue("target", targetWorkflowId).addValue("type", workflowType).addValue("queue", taskQueue)
                .addValue("start", startInputJson).addValue("messageType", messageType)
                .addValue("payload", payloadJson).addValue("digest", payloadDigest)) == 1;
    }

    public List<DispatchRow> claimDispatches(String owner, int limit, Duration lease) {
        return jdbc.query("""
                UPDATE order_workflow_dispatches d SET status = 'IN_FLIGHT', lease_owner = :owner,
                    lease_until = NOW() + CAST(:lease AS interval), attempts = d.attempts + 1, updated_at = NOW()
                WHERE d.dispatch_id IN (
                    SELECT dispatch_id FROM order_workflow_dispatches
                    WHERE (status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                       OR (status = 'IN_FLIGHT' AND lease_until < NOW())
                    ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING d.*
                """, new MapSqlParameterSource().addValue("owner", owner).addValue("limit", limit)
                .addValue("lease", lease.toSeconds() + " seconds"), DISPATCH);
    }

    public List<MessageRow> claimMessages(String owner, int limit, Duration lease) {
        return jdbc.query("""
                UPDATE order_workflow_messages m SET status = 'IN_FLIGHT', lease_owner = :owner,
                    lease_until = NOW() + CAST(:lease AS interval), attempts = m.attempts + 1, updated_at = NOW()
                WHERE m.message_id IN (
                    SELECT message_id FROM order_workflow_messages
                    WHERE (status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                       OR (status = 'IN_FLIGHT' AND lease_until < NOW())
                    ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING m.*
                """, new MapSqlParameterSource().addValue("owner", owner).addValue("limit", limit)
                .addValue("lease", lease.toSeconds() + " seconds"), MESSAGE);
    }

    public void dispatchStarted(UUID dispatchId) {
        jdbc.update("""
                UPDATE order_workflow_dispatches SET status = 'STARTED', lease_owner = NULL, lease_until = NULL,
                    last_error = NULL, updated_at = NOW()
                WHERE dispatch_id = :id
                """, Map.of("id", dispatchId));
    }

    public void dispatchRetry(UUID dispatchId, String error, Duration backoff) {
        jdbc.update("""
                UPDATE order_workflow_dispatches SET status = 'RETRY', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, available_at = NOW() + CAST(:backoff AS interval), updated_at = NOW()
                WHERE dispatch_id = :id
                """, new MapSqlParameterSource().addValue("id", dispatchId).addValue("error", truncate(error))
                .addValue("backoff", backoff.toMillis() + " milliseconds"));
    }

    public void dispatchQuarantined(UUID dispatchId, String error) {
        jdbc.update("""
                UPDATE order_workflow_dispatches SET status = 'QUARANTINED', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, updated_at = NOW()
                WHERE dispatch_id = :id
                """, Map.of("id", dispatchId, "error", truncate(error)));
    }

    public void messageDelivered(String messageId, String outcome, String replacementId) {
        jdbc.update("""
                UPDATE order_workflow_messages SET status = 'DELIVERED', delivery_outcome = :outcome,
                    replacement_message_id = :replacement, lease_owner = NULL, lease_until = NULL, last_error = NULL,
                    delivered_at = NOW(), updated_at = NOW()
                WHERE message_id = :id
                """, new MapSqlParameterSource().addValue("id", messageId).addValue("outcome", outcome)
                .addValue("replacement", replacementId));
    }

    public void messageRetry(String messageId, String error, Duration backoff) {
        jdbc.update("""
                UPDATE order_workflow_messages SET status = 'RETRY', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, available_at = NOW() + CAST(:backoff AS interval), updated_at = NOW()
                WHERE message_id = :id
                """, new MapSqlParameterSource().addValue("id", messageId).addValue("error", truncate(error))
                .addValue("backoff", backoff.toMillis() + " milliseconds"));
    }

    public void messageQuarantined(String messageId, String error) {
        jdbc.update("""
                UPDATE order_workflow_messages SET status = 'QUARANTINED', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, updated_at = NOW()
                WHERE message_id = :id
                """, Map.of("id", messageId, "error", truncate(error)));
    }

    public Optional<MessageRow> message(String messageId) {
        return jdbc.query("SELECT * FROM order_workflow_messages WHERE message_id = :id", Map.of("id", messageId),
                MESSAGE).stream().findFirst();
    }

    public long pendingMessages() {
        Long count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM order_workflow_messages WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT')", Long.class);
        return count == null ? 0 : count;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}

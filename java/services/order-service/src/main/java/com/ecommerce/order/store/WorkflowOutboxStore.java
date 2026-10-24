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
import java.util.StringJoiner;

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

    /** attempts is the monotonically increasing claim generation, also when the same pod reclaims. */
    public record DispatchCompletion(UUID dispatchId, int attempts, boolean started, String error, Duration backoff) {
        public static DispatchCompletion started(DispatchRow row) {
            return new DispatchCompletion(row.dispatchId(), row.attempts(), true, null, Duration.ZERO);
        }

        public static DispatchCompletion retry(DispatchRow row, String error, Duration backoff) {
            return new DispatchCompletion(row.dispatchId(), row.attempts(), false, error, backoff);
        }
    }

    public record MessageRow(String messageId, Long orderId, String targetWorkflowId, String workflowType,
                             String taskQueue, String startInputJson, String messageType, String payloadJson,
                             String status, int attempts, Instant createdAt) {
    }

    /** DELIVERED (with outcome), RETRY (with backoff) or QUARANTINED for one claim generation (ADR-029). */
    public record MessageCompletion(String messageId, int attempts, String status, String outcome,
                                    String replacementId, String error, Duration backoff) {
        public static MessageCompletion delivered(MessageRow row, String outcome, String replacementId) {
            return new MessageCompletion(row.messageId(), row.attempts(), "DELIVERED", outcome, replacementId, null,
                    Duration.ZERO);
        }

        public static MessageCompletion retry(MessageRow row, String error, Duration backoff) {
            return new MessageCompletion(row.messageId(), row.attempts(), "RETRY", null, null, error, backoff);
        }

        public static MessageCompletion quarantined(MessageRow row, String error) {
            return new MessageCompletion(row.messageId(), row.attempts(), "QUARANTINED", null, null, error,
                    Duration.ZERO);
        }

        public boolean retrying() {
            return "RETRY".equals(status);
        }
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
        return claimDispatches(owner, limit, lease, List.of());
    }

    /** A locally outstanding RPC must not be reclaimed even if its lease/ownership was lost meanwhile. */
    public List<DispatchRow> claimDispatches(String owner, int limit, Duration lease, List<UUID> excludedIds) {
        String exclusion = excludedIds.isEmpty() ? "" : "AND dispatch_id NOT IN (:excluded)";
        return jdbc.query("""
                UPDATE order_workflow_dispatches d SET status = 'IN_FLIGHT', lease_owner = :owner,
                    lease_until = NOW() + CAST(:lease AS interval), attempts = d.attempts + 1, updated_at = NOW()
                WHERE d.dispatch_id IN (
                    SELECT dispatch_id FROM order_workflow_dispatches
                    WHERE ((status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                       OR (status = 'IN_FLIGHT' AND lease_until < NOW()))
                    %s
                    ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING d.*
                """.formatted(exclusion), new MapSqlParameterSource().addValue("owner", owner).addValue("limit", limit)
                .addValue("excluded", excludedIds).addValue("lease", lease.toSeconds() + " seconds"), DISPATCH);
    }

    public List<MessageRow> claimMessages(String owner, int limit, Duration lease) {
        return claimMessages(owner, limit, lease, List.of());
    }

    /** Skip this coordinator's owned/backing-off lanes without preventing other targets from progressing. */
    public List<MessageRow> claimMessages(String owner, int limit, Duration lease, List<String> excludedTargets) {
        String exclusion = excludedTargets.isEmpty() ? "" : "AND target_workflow_id NOT IN (:excluded)";
        return jdbc.query("""
                UPDATE order_workflow_messages m SET status = 'IN_FLIGHT', lease_owner = :owner,
                    lease_until = NOW() + CAST(:lease AS interval), attempts = m.attempts + 1, updated_at = NOW()
                WHERE m.message_id IN (
                    SELECT message_id FROM order_workflow_messages
                    WHERE ((status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                       OR (status = 'IN_FLIGHT' AND lease_until < NOW()))
                    %s
                    ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING m.*
                """.formatted(exclusion), new MapSqlParameterSource().addValue("owner", owner).addValue("limit", limit)
                .addValue("excluded", excludedTargets).addValue("lease", lease.toSeconds() + " seconds"), MESSAGE);
    }

    /** One SQL statement/transaction for mixed successes and retries; late owners cannot overwrite a newer claim. */
    public List<UUID> completeDispatches(String owner, List<DispatchCompletion> completions) {
        if (completions.isEmpty()) return List.of();
        MapSqlParameterSource params = new MapSqlParameterSource("owner", owner);
        StringJoiner values = new StringJoiner(",");
        for (int i = 0; i < completions.size(); i++) {
            DispatchCompletion c = completions.get(i);
            values.add("(CAST(:id" + i + " AS uuid), CAST(:attempt" + i + " AS integer), CAST(:ok" + i
                    + " AS boolean), CAST(:error" + i + " AS text), CAST(:delay" + i + " AS bigint))");
            params.addValue("id" + i, c.dispatchId()).addValue("attempt" + i, c.attempts())
                    .addValue("ok" + i, c.started()).addValue("error" + i, truncate(c.error()))
                    .addValue("delay" + i, c.backoff().toMillis());
        }
        return jdbc.query("""
                UPDATE order_workflow_dispatches d
                SET status = CASE WHEN v.ok THEN 'STARTED' ELSE 'RETRY' END,
                    lease_owner = NULL, lease_until = NULL, last_error = v.error,
                    available_at = CASE WHEN v.ok THEN d.available_at
                        ELSE NOW() + v.delay * INTERVAL '1 millisecond' END, updated_at = NOW()
                FROM (VALUES %s) AS v(id, attempt, ok, error, delay)
                WHERE d.dispatch_id = v.id AND d.status = 'IN_FLIGHT'
                    AND d.lease_owner = :owner AND d.attempts = v.attempt
                RETURNING d.dispatch_id
                """.formatted(values), params, (rs, n) -> Db.uuid(rs, "dispatch_id"));
    }

    /** Extend only the current generation. No transaction/DB connection is held during Temporal RPCs. */
    public List<UUID> renewDispatches(String owner, List<DispatchRow> rows, Duration lease) {
        if (rows.isEmpty()) return List.of();
        MapSqlParameterSource params = new MapSqlParameterSource("owner", owner)
                .addValue("leaseMillis", lease.toMillis());
        StringJoiner values = new StringJoiner(",");
        for (int i = 0; i < rows.size(); i++) {
            values.add("(CAST(:id" + i + " AS uuid), CAST(:attempt" + i + " AS integer))");
            params.addValue("id" + i, rows.get(i).dispatchId()).addValue("attempt" + i, rows.get(i).attempts());
        }
        return jdbc.query("""
                UPDATE order_workflow_dispatches d
                SET lease_until = NOW() + :leaseMillis * INTERVAL '1 millisecond', updated_at = NOW()
                FROM (VALUES %s) AS v(id, attempt)
                WHERE d.dispatch_id = v.id AND d.status = 'IN_FLIGHT'
                    AND d.lease_owner = :owner AND d.attempts = v.attempt
                RETURNING d.dispatch_id
                """.formatted(values), params, (rs, n) -> Db.uuid(rs, "dispatch_id"));
    }

    public void dispatchQuarantined(UUID dispatchId, String error) {
        jdbc.update("""
                UPDATE order_workflow_dispatches SET status = 'QUARANTINED', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, updated_at = NOW()
                WHERE dispatch_id = :id
                """, Map.of("id", dispatchId, "error", truncate(error)));
    }

    /** Same fencing as {@link #completeDispatches}: one statement for delivered, retried and quarantined messages. */
    public List<String> completeMessages(String owner, List<MessageCompletion> completions) {
        if (completions.isEmpty()) return List.of();
        MapSqlParameterSource params = new MapSqlParameterSource("owner", owner);
        StringJoiner values = new StringJoiner(",");
        for (int i = 0; i < completions.size(); i++) {
            MessageCompletion c = completions.get(i);
            values.add("(CAST(:id" + i + " AS varchar), CAST(:attempt" + i + " AS integer), CAST(:status" + i
                    + " AS varchar), CAST(:outcome" + i + " AS varchar), CAST(:replacement" + i
                    + " AS varchar), CAST(:error" + i + " AS text), CAST(:delay" + i + " AS bigint))");
            params.addValue("id" + i, c.messageId()).addValue("attempt" + i, c.attempts())
                    .addValue("status" + i, c.status()).addValue("outcome" + i, c.outcome())
                    .addValue("replacement" + i, c.replacementId()).addValue("error" + i, truncate(c.error()))
                    .addValue("delay" + i, c.backoff().toMillis());
        }
        return jdbc.query("""
                UPDATE order_workflow_messages m
                SET status = v.status,
                    delivery_outcome = CASE WHEN v.status = 'DELIVERED' THEN v.outcome ELSE m.delivery_outcome END,
                    replacement_message_id = CASE WHEN v.status = 'DELIVERED' THEN v.replacement
                        ELSE m.replacement_message_id END,
                    delivered_at = CASE WHEN v.status = 'DELIVERED' THEN NOW() ELSE m.delivered_at END,
                    available_at = CASE WHEN v.status = 'RETRY' THEN NOW() + v.delay * INTERVAL '1 millisecond'
                        ELSE m.available_at END,
                    lease_owner = NULL, lease_until = NULL, last_error = v.error, updated_at = NOW()
                FROM (VALUES %s) AS v(id, attempt, status, outcome, replacement, error, delay)
                WHERE m.message_id = v.id AND m.status = 'IN_FLIGHT'
                    AND m.lease_owner = :owner AND m.attempts = v.attempt
                RETURNING m.message_id
                """.formatted(values), params, (rs, n) -> rs.getString("message_id"));
    }

    /** Extend only the current message generation, as {@link #renewDispatches} does for starts. */
    public List<String> renewMessages(String owner, List<MessageRow> rows, Duration lease) {
        if (rows.isEmpty()) return List.of();
        MapSqlParameterSource params = new MapSqlParameterSource("owner", owner)
                .addValue("leaseMillis", lease.toMillis());
        StringJoiner values = new StringJoiner(",");
        for (int i = 0; i < rows.size(); i++) {
            values.add("(CAST(:id" + i + " AS varchar), CAST(:attempt" + i + " AS integer))");
            params.addValue("id" + i, rows.get(i).messageId()).addValue("attempt" + i, rows.get(i).attempts());
        }
        return jdbc.query("""
                UPDATE order_workflow_messages m
                SET lease_until = NOW() + :leaseMillis * INTERVAL '1 millisecond', updated_at = NOW()
                FROM (VALUES %s) AS v(id, attempt)
                WHERE m.message_id = v.id AND m.status = 'IN_FLIGHT'
                    AND m.lease_owner = :owner AND m.attempts = v.attempt
                RETURNING m.message_id
                """.formatted(values), params, (rs, n) -> rs.getString("message_id"));
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

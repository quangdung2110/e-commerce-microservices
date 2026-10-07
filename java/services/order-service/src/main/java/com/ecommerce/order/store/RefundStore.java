package com.ecommerce.order.store;

import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.Mode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Lifetime refund case, selected lines and Admin pre-dispatch attempts (data.md §3.6-3.7). */
@Repository
public class RefundStore {

    private static final Set<String> UPDATABLE = Set.of(
            "case_status", "inventory_refund_scope_id", "inventory_scope_registered_revision",
            "inventory_scope_inspection_completed_at", "inventory_inspection_event_id",
            "inventory_inspection_payload_hash", "inventory_scope_revision", "inventory_inspection_lines",
            "inventory_scope_finalization_status", "inventory_scope_finalization_generation",
            "inventory_scope_finalization_operation", "inventory_scope_finalization_reason",
            "inventory_scope_finalization_command_id", "inventory_scope_finalization_decision_id",
            "inventory_scope_terminal_state", "inventory_scope_finalization_response_revision",
            "inventory_scope_finalization_response_hash", "inventory_scope_finalized_at",
            "refund_command_id", "refund_transaction_id", "refund_failure_code", "refund_reversal_reason_code",
            "manual_verification_reason", "scope_locked_at", "resolved_at");

    public record CaseRow(long orderId, String refundRequestId, String workflowId, String triggerSource,
                          String reasonCode, String reasonDetails, String requestPrincipalId,
                          String requestIdempotencyKey, String requestDigest, UUID operationId, CaseStatus status,
                          Mode mode, long amountLockedMinor, String refundScopeId, Long scopeRegisteredRevision,
                          Instant inspectionCompletedAt, UUID inspectionEventId, String inspectionPayloadHash,
                          Long scopeRevision, String inspectionLinesJson, FinalizationStatus finalizationStatus,
                          int finalizationGeneration, String finalizationOperation, String finalizationReason,
                          String finalizationCommandId, UUID finalizationDecisionId, String scopeTerminalState,
                          Long finalizationResponseRevision, String finalizationResponseHash, Instant finalizedAt,
                          Instant committedReleaseAt, String releaseCommandId, String releaseCorrelationId,
                          Long releaseReservationRevision, String releasedLineIdsJson, String releaseResponseHash,
                          String refundCommandId, String refundTransactionId, String refundFailureCode,
                          String reversalReasonCode, String manualVerificationReason, Instant startedAt,
                          Instant scopeLockedAt, Instant resolvedAt, Instant updatedAt) {
    }

    public record CaseLineRow(long orderLineId, int expectedQuantity, long unitPriceMinor, long discountShareMinor,
                              long netAmountMinor) {
    }

    public record AdminAttemptRow(UUID attemptId, long orderId, String workflowId, String inventoryCommandId,
                                  UUID operationId, String requestDigest, String reasonCode, String reasonDetails,
                                  String actorId, String status, String outcomeDigest, String errorCode,
                                  Instant createdAt) {
    }

    private static final RowMapper<CaseRow> CASE = (rs, i) -> new CaseRow(rs.getLong("order_id"),
            rs.getString("refund_request_id"), rs.getString("workflow_id"), rs.getString("trigger_source"),
            rs.getString("reason_code"), rs.getString("reason_details"), rs.getString("request_principal_id"),
            rs.getString("request_idempotency_key"), rs.getString("request_digest"), Db.uuid(rs, "operation_id"),
            CaseStatus.valueOf(rs.getString("case_status")), Mode.valueOf(rs.getString("refund_mode")),
            rs.getLong("refund_amount_locked_minor"), rs.getString("inventory_refund_scope_id"),
            Db.nullableLong(rs, "inventory_scope_registered_revision"),
            Db.instant(rs, "inventory_scope_inspection_completed_at"), Db.uuid(rs, "inventory_inspection_event_id"),
            rs.getString("inventory_inspection_payload_hash"), Db.nullableLong(rs, "inventory_scope_revision"),
            rs.getString("inventory_inspection_lines"),
            FinalizationStatus.valueOf(rs.getString("inventory_scope_finalization_status")),
            rs.getInt("inventory_scope_finalization_generation"), rs.getString("inventory_scope_finalization_operation"),
            rs.getString("inventory_scope_finalization_reason"), rs.getString("inventory_scope_finalization_command_id"),
            Db.uuid(rs, "inventory_scope_finalization_decision_id"), rs.getString("inventory_scope_terminal_state"),
            Db.nullableLong(rs, "inventory_scope_finalization_response_revision"),
            rs.getString("inventory_scope_finalization_response_hash"), Db.instant(rs, "inventory_scope_finalized_at"),
            Db.instant(rs, "inventory_committed_release_at"), rs.getString("inventory_release_command_id"),
            rs.getString("inventory_release_correlation_id"),
            Db.nullableLong(rs, "inventory_release_reservation_revision"),
            rs.getString("inventory_released_order_line_ids"), rs.getString("inventory_release_response_hash"),
            rs.getString("refund_command_id"), rs.getString("refund_transaction_id"),
            rs.getString("refund_failure_code"), rs.getString("refund_reversal_reason_code"),
            rs.getString("manual_verification_reason"), Db.instant(rs, "started_at"),
            Db.instant(rs, "scope_locked_at"), Db.instant(rs, "resolved_at"), Db.instant(rs, "updated_at"));

    private static final RowMapper<AdminAttemptRow> ATTEMPT = (rs, i) -> new AdminAttemptRow(
            Db.uuid(rs, "attempt_id"), rs.getLong("order_id"), rs.getString("workflow_id"),
            rs.getString("inventory_command_id"), Db.uuid(rs, "operation_id"), rs.getString("request_digest"),
            rs.getString("reason_code"), rs.getString("reason_details"), rs.getString("actor_id"),
            rs.getString("status"), rs.getString("outcome_digest"), rs.getString("error_code"),
            Db.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public RefundStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NewCase(long orderId, String refundRequestId, String workflowId, String triggerSource,
                          String reasonCode, String reasonDetails, String principal, String idempotencyKey,
                          String requestDigest, UUID operationId, CaseStatus status, Mode mode, long amountMinor,
                          FinalizationStatus finalizationStatus, Instant startedAt) {
    }

    public void insertCase(NewCase c) {
        jdbc.update("""
                INSERT INTO order_refund_cases (order_id, refund_request_id, workflow_id, trigger_source, reason_code,
                    reason_details, request_principal_id, request_idempotency_key, request_digest, operation_id,
                    case_status, refund_mode, refund_amount_locked_minor, inventory_scope_finalization_status,
                    started_at, scope_locked_at)
                VALUES (:order, :request, :workflow, :trigger, :reason, :details, :principal, :key, :digest,
                    :operation, :status, :mode, :amount, :finalization, :started, :started)
                """, new MapSqlParameterSource().addValue("order", c.orderId()).addValue("request", c.refundRequestId())
                .addValue("workflow", c.workflowId()).addValue("trigger", c.triggerSource())
                .addValue("reason", c.reasonCode()).addValue("details", c.reasonDetails())
                .addValue("principal", c.principal()).addValue("key", c.idempotencyKey())
                .addValue("digest", c.requestDigest()).addValue("operation", c.operationId())
                .addValue("status", c.status().name()).addValue("mode", c.mode().name())
                .addValue("amount", c.amountMinor()).addValue("finalization", c.finalizationStatus().name())
                .addValue("started", Db.ts(c.startedAt())));
    }

    public void insertCaseLine(long orderId, CaseLineRow line) {
        jdbc.update("""
                INSERT INTO order_refund_case_lines (refund_case_order_id, order_line_id, expected_quantity,
                    unit_price_snapshot_minor, discount_share_snapshot_minor, net_amount_snapshot_minor)
                VALUES (:order, :line, :quantity, :price, :share, :net)
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("line", line.orderLineId())
                .addValue("quantity", line.expectedQuantity()).addValue("price", line.unitPriceMinor())
                .addValue("share", line.discountShareMinor()).addValue("net", line.netAmountMinor()));
    }

    public Optional<CaseRow> find(long orderId) {
        return jdbc.query("SELECT * FROM order_refund_cases WHERE order_id = :order", Map.of("order", orderId), CASE)
                .stream().findFirst();
    }

    public Optional<CaseRow> lock(long orderId) {
        return jdbc.query("SELECT * FROM order_refund_cases WHERE order_id = :order FOR UPDATE",
                Map.of("order", orderId), CASE).stream().findFirst();
    }

    public Optional<CaseRow> findByScope(String scopeId) {
        return jdbc.query("SELECT * FROM order_refund_cases WHERE inventory_refund_scope_id = :scope",
                Map.of("scope", scopeId), CASE).stream().findFirst();
    }

    public Optional<CaseRow> findByRefundTransaction(String refundTransactionId) {
        return jdbc.query("SELECT * FROM order_refund_cases WHERE refund_transaction_id = :txn",
                Map.of("txn", refundTransactionId), CASE).stream().findFirst();
    }

    public List<CaseLineRow> lines(long orderId) {
        return jdbc.query("""
                SELECT * FROM order_refund_case_lines WHERE refund_case_order_id = :order ORDER BY order_line_id
                """, Map.of("order", orderId), (rs, i) -> new CaseLineRow(rs.getLong("order_line_id"),
                rs.getInt("expected_quantity"), rs.getLong("unit_price_snapshot_minor"),
                rs.getLong("discount_share_snapshot_minor"), rs.getLong("net_amount_snapshot_minor")));
    }

    /** Writes whitelisted case columns plus updated_at; JSON columns take a JSON string. */
    public void update(long orderId, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("order", orderId);
        String set = new LinkedHashMap<>(changes).entrySet().stream().map(e -> {
            String column = e.getKey();
            if (!UPDATABLE.contains(column)) {
                throw new IllegalArgumentException("Column not updatable: " + column);
            }
            Object value = e.getValue();
            if (value instanceof Instant instant) {
                value = Db.ts(instant);
            } else if (value instanceof Enum<?> en) {
                value = en.name();
            }
            params.addValue(column, value);
            boolean json = column.equals("inventory_inspection_lines");
            return column + " = " + (json ? "CAST(:" + column + " AS jsonb)" : ":" + column);
        }).collect(Collectors.joining(", "));
        jdbc.update("UPDATE order_refund_cases SET " + set + ", updated_at = NOW() WHERE order_id = :order", params);
    }

    /** Admin release evidence is written once, with the case insert transaction. */
    public void recordAdminReleaseEvidence(long orderId, String commandId, String correlationId, long revision,
                                           String releasedLineIdsJson, String responseHash, Instant releasedAt) {
        jdbc.update("""
                UPDATE order_refund_cases SET inventory_committed_release_at = :at, inventory_release_command_id = :command,
                    inventory_release_correlation_id = :correlation, inventory_release_reservation_revision = :revision,
                    inventory_released_order_line_ids = CAST(:lines AS jsonb), inventory_release_response_hash = :hash,
                    updated_at = NOW()
                WHERE order_id = :order
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("at", Db.ts(releasedAt))
                .addValue("command", commandId).addValue("correlation", correlationId).addValue("revision", revision)
                .addValue("lines", releasedLineIdsJson).addValue("hash", responseHash));
    }

    public List<CaseRow> unresolvedFinalization(int limit) {
        return jdbc.query("""
                SELECT * FROM order_refund_cases
                WHERE inventory_scope_finalization_status IN ('PENDING', 'RECOVERY_REQUIRED')
                  AND updated_at < NOW() - INTERVAL '2 minutes'
                ORDER BY updated_at LIMIT :limit
                """, Map.of("limit", limit), CASE);
    }

    public void insertAdminAttempt(AdminAttemptRow a) {
        jdbc.update("""
                INSERT INTO order_admin_refund_attempts (attempt_id, order_id, workflow_id, inventory_command_id,
                    operation_id, request_digest, reason_code, reason_details, actor_id, status)
                VALUES (:id, :order, :workflow, :command, :operation, :digest, :reason, :details, :actor, 'PENDING')
                """, new MapSqlParameterSource().addValue("id", a.attemptId()).addValue("order", a.orderId())
                .addValue("workflow", a.workflowId()).addValue("command", a.inventoryCommandId())
                .addValue("operation", a.operationId()).addValue("digest", a.requestDigest())
                .addValue("reason", a.reasonCode()).addValue("details", a.reasonDetails())
                .addValue("actor", a.actorId()));
    }

    public Optional<AdminAttemptRow> adminAttempt(UUID attemptId) {
        return jdbc.query("SELECT * FROM order_admin_refund_attempts WHERE attempt_id = :id",
                Map.of("id", attemptId), ATTEMPT).stream().findFirst();
    }

    public Optional<AdminAttemptRow> activeAdminAttempt(long orderId) {
        return jdbc.query("""
                SELECT * FROM order_admin_refund_attempts
                WHERE order_id = :order AND status IN ('PENDING', 'RECOVERY_REQUIRED')
                """, Map.of("order", orderId), ATTEMPT).stream().findFirst();
    }

    public void updateAdminAttempt(UUID attemptId, String status, String outcomeDigest, String errorCode) {
        jdbc.update("""
                UPDATE order_admin_refund_attempts SET status = :status,
                    outcome_digest = COALESCE(:digest, outcome_digest), error_code = :error, updated_at = NOW()
                WHERE attempt_id = :id
                """, new MapSqlParameterSource().addValue("id", attemptId).addValue("status", status)
                .addValue("digest", outcomeDigest).addValue("error", errorCode));
    }
}

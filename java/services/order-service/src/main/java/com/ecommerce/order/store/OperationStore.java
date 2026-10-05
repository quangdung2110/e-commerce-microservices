package com.ecommerce.order.store;

import com.ecommerce.order.domain.OperationTypes;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Async operation projection with scoped idempotency identity (data.md §3.9). */
@Repository
public class OperationStore {

    public record OperationRow(UUID operationId, long orderId, String ownerPrincipalId, String ownerRole,
                               OperationTypes.Type type, String idempotencyKey, String requestDigest,
                               OperationTypes.Status status, String resultReference, String problemJson,
                               Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    private static final RowMapper<OperationRow> ROW = (rs, i) -> new OperationRow(Db.uuid(rs, "operation_id"),
            rs.getLong("order_id"), rs.getString("owner_principal_id"), rs.getString("owner_role"),
            OperationTypes.Type.valueOf(rs.getString("operation_type")), rs.getString("idempotency_key"),
            rs.getString("request_digest"), OperationTypes.Status.valueOf(rs.getString("status")),
            rs.getString("result_reference"), rs.getString("problem"), Db.instant(rs, "created_at"),
            Db.instant(rs, "updated_at"), Db.instant(rs, "completed_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public OperationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OperationRow> findByScope(String principal, OperationTypes.Type type, String key) {
        return jdbc.query("""
                SELECT * FROM order_operations
                WHERE owner_principal_id = :principal AND operation_type = :type AND idempotency_key = :key
                """, Map.of("principal", principal, "type", type.name(), "key", key), ROW).stream().findFirst();
    }

    public Optional<OperationRow> find(UUID operationId) {
        return jdbc.query("SELECT * FROM order_operations WHERE operation_id = :id", Map.of("id", operationId), ROW)
                .stream().findFirst();
    }

    public List<OperationRow> openByOrder(long orderId, OperationTypes.Type type) {
        return jdbc.query("""
                SELECT * FROM order_operations
                WHERE order_id = :order AND operation_type = :type AND status IN ('ACCEPTED', 'RUNNING')
                ORDER BY created_at
                """, Map.of("order", orderId, "type", type.name()), ROW);
    }

    public void insert(UUID operationId, long orderId, String principal, String role, OperationTypes.Type type,
                       String key, String digest) {
        jdbc.update("""
                INSERT INTO order_operations (operation_id, order_id, owner_principal_id, owner_role, operation_type,
                    idempotency_key, request_digest, status)
                VALUES (:id, :order, :principal, :role, :type, :key, :digest, 'ACCEPTED')
                """, new MapSqlParameterSource().addValue("id", operationId).addValue("order", orderId)
                .addValue("principal", principal).addValue("role", role).addValue("type", type.name())
                .addValue("key", key).addValue("digest", digest));
    }

    /** Moves a non-terminal operation; terminal operations are immutable. */
    public boolean transition(UUID operationId, OperationTypes.Status status, String resultReference,
                              String problemJson) {
        return jdbc.update("""
                UPDATE order_operations SET status = :status,
                    result_reference = COALESCE(:ref, result_reference),
                    problem = CAST(:problem AS jsonb), updated_at = NOW(),
                    completed_at = CASE WHEN :terminal THEN NOW() ELSE completed_at END
                WHERE operation_id = :id AND status NOT IN ('SUCCEEDED', 'REJECTED', 'FAILED')
                """, new MapSqlParameterSource().addValue("id", operationId).addValue("status", status.name())
                .addValue("ref", resultReference).addValue("problem", problemJson)
                .addValue("terminal", status.terminal())) == 1;
    }
}

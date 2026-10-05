package com.ecommerce.order.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Remote command checkpoints (data.md §3.9): one stable command identity and frozen
 * request per {@code (order, lane, phase, operation, resource, generation)}, persisted
 * before the call; the matching outcome is recorded after it.
 */
@Repository
public class CheckpointStore {

    public record CheckpointRow(UUID id, long orderId, String lane, String phase, String operation,
                                String resourceId, int generation, String commandId, String requestJson,
                                String requestDigest, String outcome, String errorCode, String responseJson,
                                String responseDigest, int attempts, Instant createdAt, Instant updatedAt) {

        public boolean accepted() {
            return "ACCEPTED".equals(outcome);
        }

        public boolean rejected() {
            return "REJECTED".equals(outcome);
        }
    }

    public record Key(long orderId, String lane, String phase, String operation, String resourceId, int generation) {
    }

    private static final RowMapper<CheckpointRow> ROW = (rs, i) -> new CheckpointRow(Db.uuid(rs, "id"),
            rs.getLong("order_id"), rs.getString("workflow_lane"), rs.getString("phase"), rs.getString("operation"),
            rs.getString("resource_id"), rs.getInt("generation"), rs.getString("command_id"), rs.getString("request"),
            rs.getString("request_digest"), rs.getString("outcome"), rs.getString("error_code"),
            rs.getString("response"), rs.getString("response_digest"), rs.getInt("attempts"),
            Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public CheckpointStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Freezes command ID/request on first call; later calls return the frozen row unchanged. */
    public CheckpointRow freeze(Key key, String commandId, String requestJson, String requestDigest) {
        jdbc.update("""
                INSERT INTO order_saga_checkpoints (id, order_id, workflow_lane, phase, operation, resource_id,
                    generation, command_id, request, request_digest, outcome)
                VALUES (:id, :order, :lane, :phase, :operation, :resource, :generation, :command,
                    CAST(:request AS jsonb), :digest, 'PENDING')
                ON CONFLICT (order_id, workflow_lane, phase, operation, resource_id, generation) DO NOTHING
                """, params(key).addValue("id", UUID.randomUUID()).addValue("command", commandId)
                .addValue("request", requestJson).addValue("digest", requestDigest));
        return find(key).orElseThrow();
    }

    public Optional<CheckpointRow> find(Key key) {
        return jdbc.query("""
                SELECT * FROM order_saga_checkpoints
                WHERE order_id = :order AND workflow_lane = :lane AND phase = :phase AND operation = :operation
                  AND resource_id = :resource AND generation = :generation
                """, params(key), ROW).stream().findFirst();
    }

    public Optional<CheckpointRow> findByCommand(String commandId) {
        return jdbc.query("SELECT * FROM order_saga_checkpoints WHERE command_id = :command",
                Map.of("command", commandId), ROW).stream().findFirst();
    }

    public List<CheckpointRow> forOrder(long orderId) {
        return jdbc.query("SELECT * FROM order_saga_checkpoints WHERE order_id = :order ORDER BY created_at",
                Map.of("order", orderId), ROW);
    }

    /**
     * Records the latest outcome. ACCEPTED/REJECTED are durable remote results and are
     * never downgraded to UNKNOWN by a later transport failure.
     */
    public void record(UUID id, String outcome, String errorCode, String responseJson, String responseDigest) {
        jdbc.update("""
                UPDATE order_saga_checkpoints SET
                    outcome = CASE WHEN outcome IN ('ACCEPTED', 'REJECTED') AND :outcome = 'UNKNOWN'
                                   THEN outcome ELSE :outcome END,
                    error_code = CASE WHEN outcome IN ('ACCEPTED', 'REJECTED') AND :outcome = 'UNKNOWN'
                                   THEN error_code ELSE :error END,
                    response = COALESCE(CAST(:response AS jsonb), response),
                    response_digest = COALESCE(:responseDigest, response_digest),
                    attempts = attempts + 1, updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("outcome", outcome)
                .addValue("error", errorCode).addValue("response", responseJson)
                .addValue("responseDigest", responseDigest));
    }

    private static MapSqlParameterSource params(Key key) {
        return new MapSqlParameterSource().addValue("order", key.orderId()).addValue("lane", key.lane())
                .addValue("phase", key.phase()).addValue("operation", key.operation())
                .addValue("resource", key.resourceId() == null ? "" : key.resourceId())
                .addValue("generation", key.generation());
    }
}

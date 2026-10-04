package com.ecommerce.inventory.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code command_execution}: durable terminal result per {@code (caller_namespace, command_id)}. */
@Repository
public class CommandStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CommandStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims the command identity. A concurrent transaction holding the same key makes this
     * insert wait on its row lock, bounded by the transaction lock timeout.
     *
     * @return true when this transaction owns a fresh command
     */
    public boolean claim(String namespace, String commandId, String operation, String fingerprint,
                         String correlationId, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO command_execution (caller_namespace, command_id, operation, fingerprint,
                                               correlation_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (caller_namespace, command_id) DO NOTHING
                """, namespace, commandId, operation, fingerprint, correlationId, Timestamp.from(now));
        return inserted == 1;
    }

    public void complete(String namespace, String commandId, String result, int httpStatus, String errorCode,
                         String outcome, String resourceId, JsonNode responseBody) {
        jdbc.update("""
                UPDATE command_execution
                SET result = ?, http_status = ?, error_code = ?, outcome = ?, resource_id = ?,
                    response_body = ?::jsonb
                WHERE caller_namespace = ? AND command_id = ?
                """, result, httpStatus, errorCode, outcome, resourceId, json(responseBody), namespace, commandId);
    }

    public Optional<StoredCommand> find(String namespace, String commandId) {
        List<StoredCommand> rows = jdbc.query("""
                SELECT caller_namespace, command_id, operation, fingerprint, result, http_status, error_code,
                       outcome, resource_id, response_body::text AS response_body, correlation_id, recorded_at
                FROM command_execution
                WHERE caller_namespace = ? AND command_id = ? AND result IS NOT NULL
                """, (rs, i) -> new StoredCommand(
                rs.getString("caller_namespace"),
                rs.getString("command_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getString("result"),
                rs.getInt("http_status"),
                rs.getString("error_code"),
                rs.getString("outcome"),
                rs.getString("resource_id"),
                parse(rs.getString("response_body")),
                rs.getString("correlation_id"),
                rs.getTimestamp("recorded_at").toInstant()), namespace, commandId);
        return rows.stream().findFirst();
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
            return value == null ? null : objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record StoredCommand(
            String namespace,
            String commandId,
            String operation,
            String fingerprint,
            String result,
            int httpStatus,
            String errorCode,
            String outcome,
            String resourceId,
            JsonNode responseBody,
            String correlationId,
            Instant recordedAt) {
    }
}

package com.ecommerce.inventory.store;

import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Append-only {@code audit_entry} and successful-only {@code operation_identity}. */
@Repository
public class EvidenceStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvidenceStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Unambiguous key: each part is length-prefixed so separators inside IDs cannot collide. */
    public static String operationKey(String type, String... parts) {
        return type + Stream.of(parts).map(p -> "|" + p.length() + ":" + p).collect(Collectors.joining());
    }

    /** Rejects early when another accepted operation already owns this business identity. */
    public void requireUnclaimed(String operationKey) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM operation_identity WHERE operation_key = ?",
                Integer.class, operationKey);
        if (n != null && n > 0) {
            throw new DomainRejection(ErrorCode.OPERATION_IDENTITY_CONFLICT,
                    "This business operation was already recorded by another command");
        }
    }

    /**
     * Claims the identity for a successful operation. A concurrent winner surfaces as a
     * duplicate key, which becomes the same durable conflict.
     *
     * @return the generated operation ID
     */
    public String claim(String operationKey, String operationType, CommandContext context) {
        String operationId = Ids.newId();
        try {
            jdbc.update("""
                    INSERT INTO operation_identity (operation_key, operation_type, operation_id, command_id, recorded_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, operationKey, operationType, operationId, context.commandId(), Timestamp.from(context.now()));
        } catch (DuplicateKeyException e) {
            throw new DomainRejection(ErrorCode.OPERATION_IDENTITY_CONFLICT,
                    "This business operation was already recorded by another command");
        }
        return operationId;
    }

    public void audit(CommandContext context, String targetType, String targetId, String reason,
                      String evidenceReference, Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_entry (recorded_at, actor_type, actor_id, actor_role, session_id, caller_namespace,
                    command_id, operation, target_type, target_id, reason, evidence_reference, before_state,
                    after_state, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, Timestamp.from(context.now()), context.actorType(), context.actorId(), context.actorRole(),
                context.sessionId(), context.namespace(), context.commandId(), context.operation(), targetType,
                targetId, reason, evidenceReference, json(before), json(after), context.correlationId(),
                context.causationId());
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

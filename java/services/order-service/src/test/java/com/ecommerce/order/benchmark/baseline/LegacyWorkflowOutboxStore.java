package com.ecommerce.order.benchmark.baseline;

import com.ecommerce.order.store.WorkflowOutboxStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Frozen pre-optimization per-row start ACK SQL and pre-ADR-029 message ACK SQL, for the paired
 * benchmark only. Claim SQL is inherited unchanged from the production store. Never a Spring bean. */
public class LegacyWorkflowOutboxStore extends WorkflowOutboxStore {
    private final NamedParameterJdbcTemplate jdbc;

    public LegacyWorkflowOutboxStore(NamedParameterJdbcTemplate jdbc) { super(jdbc); this.jdbc = jdbc; }

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
                """, new MapSqlParameterSource().addValue("id", dispatchId).addValue("error", error)
                .addValue("backoff", backoff.toMillis() + " milliseconds"));
    }

    // Frozen per-row, unfenced message ACK SQL used by both baselines before ADR-029.

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
                """, new MapSqlParameterSource().addValue("id", messageId).addValue("error", error)
                .addValue("backoff", backoff.toMillis() + " milliseconds"));
    }

    public void messageQuarantined(String messageId, String error) {
        jdbc.update("""
                UPDATE order_workflow_messages SET status = 'QUARANTINED', lease_owner = NULL, lease_until = NULL,
                    last_error = :error, updated_at = NOW()
                WHERE message_id = :id
                """, Map.of("id", messageId, "error", error));
    }
}

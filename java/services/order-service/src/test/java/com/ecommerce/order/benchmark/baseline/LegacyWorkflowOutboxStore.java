package com.ecommerce.order.benchmark.baseline;

import com.ecommerce.order.store.WorkflowOutboxStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Frozen pre-optimization per-row start ACK SQL, for the paired benchmark only.
 * Claim/message SQL is inherited unchanged from the production store. Never a Spring bean. */
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
}

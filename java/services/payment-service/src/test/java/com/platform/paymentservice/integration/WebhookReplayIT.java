package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.service.IdempotencyExecutor;
import com.platform.paymentservice.application.service.WebhookReplayCommand;
import com.platform.paymentservice.application.service.WebhookReplayResult;
import com.platform.paymentservice.application.service.WebhookReplayService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookReplayIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final String EVENT_ID = "evt_manual_replay_1";

    @Test
    void terminal_verified_row_is_reset_with_new_budget_fence_and_maker_checker_audit() {
        seed("PROCESSED", true, null, 8, 4);
        var service = service();
        var command = command("replay-idempotency-key-0001", "INC-4201");

        WebhookReplayResult first = service.replay(command, "ops-worker-a");
        WebhookReplayResult replay = service.replay(command, "ops-worker-b");

        assertThat(first.outcome()).isEqualTo(WebhookReplayResult.Outcome.SCHEDULED);
        assertThat(replay.outcome()).isEqualTo(WebhookReplayResult.Outcome.SCHEDULED);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForMap("SELECT * FROM webhook_logs WHERE stripe_event_id=?", EVENT_ID))
                .containsEntry("status", "RETRY_WAIT")
                .containsEntry("processing_attempts", 0)
                .containsEntry("lease_token", 5L)
                .containsEntry("replay_actor", "oncall-maker")
                .containsEntry("replay_approver", "oncall-checker")
                .containsEntry("replay_cause_ref", "INC-4201");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_idempotency", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void active_processing_lease_is_not_overridden() {
        seed("PROCESSING", true, NOW.plusSeconds(30), 2, 7);

        WebhookReplayResult result = service().replay(
                command("replay-idempotency-key-0002", "INC-4202"), "ops-worker-a");

        assertThat(result.outcome()).isEqualTo(WebhookReplayResult.Outcome.BUSY);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM webhook_logs WHERE stripe_event_id=?",
                Long.class, EVENT_ID)).isEqualTo(7L);
    }

    @Test
    void crypto_shredded_payload_is_gone_and_never_scheduled() {
        seed("DEAD", false, null, 8, 1);

        WebhookReplayResult result = service().replay(
                command("replay-idempotency-key-0003", "INC-4203"), "ops-worker-a");

        assertThat(result.outcome()).isEqualTo(WebhookReplayResult.Outcome.PAYLOAD_SHREDDED);
        assertThat(result.statusCode()).isEqualTo(410);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE stripe_event_id=?",
                String.class, EVENT_ID)).isEqualTo("DEAD");
    }

    @Test
    void same_key_with_different_cause_is_rejected() {
        seed("PROCESSED", true, null, 0, 0);
        var service = service();
        service.replay(command("replay-idempotency-key-0004", "INC-4204"), "ops-worker-a");

        WebhookReplayResult result = service.replay(
                command("replay-idempotency-key-0004", "INC-CHANGED"), "ops-worker-b");

        assertThat(result.outcome()).isEqualTo(WebhookReplayResult.Outcome.IDEMPOTENCY_KEY_REUSED);
        assertThat(result.statusCode()).isEqualTo(422);
    }

    private WebhookReplayService service() {
        ClockPort clock = () -> NOW;
        return new WebhookReplayService(webhookProcessing,
                new IdempotencyExecutor(idempotency, clock), clock,
                Duration.ofSeconds(45), Duration.ofDays(90));
    }

    private static WebhookReplayCommand command(String key, String cause) {
        return new WebhookReplayCommand(EVENT_ID, "oncall-maker", "oncall-checker",
                "correlation-4201", key, cause);
    }

    private void seed(String status, boolean ciphertext, Instant leaseExpiresAt,
                      int attempts, long leaseToken) {
        jdbc.update("""
                INSERT INTO webhook_logs
                  (stripe_event_id,event_type,event_created_at,livemode,raw_body_ciphertext,
                   raw_body_key_id,raw_body_sha256,stripe_signature_ciphertext,signing_secret_id,
                   verified_at,status,processing_attempts,lease_owner,lease_token,
                   lease_expires_at,received_at,processed_at)
                VALUES (?, 'payment_intent.succeeded', ?::timestamptz, false, ?, ?, ?, ?,
                        'key-1', ?::timestamptz, ?, ?, ?, ?, ?, ?::timestamptz,
                        CASE WHEN ? IN ('PROCESSED','IGNORED','DEAD') THEN ?::timestamptz ELSE NULL END)
                """, EVENT_ID, NOW.minusSeconds(5).toString(),
                ciphertext ? new byte[]{1, 2, 3} : null, ciphertext ? "kms-key-1" : null,
                "0".repeat(64), ciphertext ? new byte[]{4, 5, 6} : null,
                NOW.minusSeconds(4).toString(), status, attempts,
                "PROCESSING".equals(status) ? "worker-active" : null, leaseToken,
                leaseExpiresAt == null ? null : java.sql.Timestamp.from(leaseExpiresAt),
                NOW.minusSeconds(4).toString(), status, NOW.minusSeconds(1).toString());
    }
}

package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.service.BalanceReconciliationService;
import com.platform.paymentservice.application.service.IdempotencyExecutor;
import com.platform.paymentservice.application.service.ReconciliationOperationsService;
import com.platform.paymentservice.application.service.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationOperationsIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void freeze_is_durable_idempotent_and_used_by_manual_run() {
        var json = JsonMapper.builder().findAndAddModules().build();
        IdGeneratorPort ids = new IdGeneratorPort() {
            @Override public String nextPaymentTransactionId() { return "unused"; }
            @Override public String nextProviderOperationId() { return "unused"; }
            @Override public String nextReviewCaseId() { return "unused"; }
            @Override public UUID nextEventId() { return UUID.randomUUID(); }
        };
        ProviderCurrentFactPort facts = new ProviderCurrentFactPort() {
            @Override public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String id) {
                throw new AssertionError("empty database must not call provider");
            }
            @Override public LookupResult retrieveRefund(StripeWebhookEvent source, String id) {
                throw new AssertionError("empty database must not call provider");
            }
            @Override public LookupResult retrieveDispute(StripeWebhookEvent source, String id) {
                throw new AssertionError("empty database must not call provider");
            }
        };
        ReconciliationService objectState = new ReconciliationService(
                reconciliation, facts, commits, ids, () -> NOW, json);
        ProviderBalancePort providerBalance = (from, to, cursor, limit) ->
                new ProviderBalancePort.Page(List.of(), null, false);
        BalanceReconciliationService balance = new BalanceReconciliationService(
                reconciliation, providerBalance, balanceLedger, ids, () -> NOW, json);
        ReconciliationOperationsService service = new ReconciliationOperationsService(
                objectState, balance, reconciliation,
                new IdempotencyExecutor(idempotency, () -> NOW), () -> NOW, json,
                Duration.ofSeconds(45), Duration.ofDays(7), 100, false);
        var freeze = new ReconciliationOperationsService.FreezeCommand(true, "INC-42",
                "maker-user", "checker-user", "corr-1", "freeze-idempotency-0001");

        assertThat(service.freeze(freeze, "owner-1").outcome())
                .isEqualTo(ReconciliationOperationsService.Outcome.COMPLETED);
        assertThat(service.freeze(freeze, "owner-2").outcome())
                .isEqualTo(ReconciliationOperationsService.Outcome.COMPLETED);
        assertThat(reconciliation.currentFreeze(false)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE lane='CONTROL'", Integer.class))
                .isEqualTo(1);

        var start = new ReconciliationOperationsService.StartCommand("OBJECT_STATE",
                NOW.minusSeconds(3600), NOW, "maker-user", "checker-user", "corr-2",
                "reconcile-idempotency-01");
        assertThat(service.start(start, "owner-3").outcome())
                .isEqualTo(ReconciliationOperationsService.Outcome.ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT auto_correction_frozen FROM reconciliation_runs
                 WHERE lane='OBJECT_STATE'
                """, Boolean.class)).isTrue();
    }
}

package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.platform.paymentservice.application.service.BalanceReconciliationService;
import com.platform.paymentservice.application.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceReconciliationIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,expires_at,succeeded_at)
                VALUES ('TXN_BALANCE',801,1,?,10000,'VND','pi_balance','ch_balance',
                        'SUCCESS',?::timestamptz,?::timestamptz)
                """, UUID.randomUUID(), "2026-09-25T09:00:00Z", "2026-09-25T09:00:00Z");
    }

    @Test
    void capture_and_fee_are_posted_as_balanced_deduplicated_journals() {
        AtomicInteger journalSequence = new AtomicInteger();
        IdGeneratorPort ids = new IdGeneratorPort() {
            @Override public String nextPaymentTransactionId() { return "unused"; }
            @Override public String nextProviderOperationId() { return "unused"; }
            @Override public String nextReviewCaseId() { return "unused"; }
            @Override public String nextFinancialJournalId() {
                return "FJN_BALANCE_" + journalSequence.incrementAndGet();
            }
            @Override public UUID nextEventId() { return UUID.randomUUID(); }
        };
        ProviderBalancePort provider = (from, to, cursor, limit) -> new ProviderBalancePort.Page(
                List.of(new ProviderBalancePort.Entry("txn_balance", "charge", "ch_balance",
                        10000, 300, 9700, "VND", NOW.minusSeconds(60))),
                "txn_balance", false);
        BalanceReconciliationService service = new BalanceReconciliationService(
                reconciliation, provider, balanceLedger, ids, () -> NOW,
                JsonMapper.builder().findAndAddModules().build());

        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT captured_amount_minor FROM reconciliation_runs", Long.class))
                .isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT fee_amount_minor FROM reconciliation_runs", Long.class))
                .isEqualTo(300L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT journal_id FROM financial_ledger_entries GROUP BY journal_id
                  HAVING sum(amount_minor) FILTER (WHERE direction='DEBIT') =
                         sum(amount_minor) FILTER (WHERE direction='CREDIT')) balanced
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void positive_dispute_adjustment_is_posted_as_reinstatement() {
        jdbc.update("""
                INSERT INTO payment_disputes
                  (id,payment_transaction_id,order_id,provider_dispute_id,amount_minor,currency,
                   reason_code,status,funds_state,opened_at,closed_at,
                   last_provider_event_created_at,last_provider_event_id)
                VALUES ('DSP_BALANCE','TXN_BALANCE',801,'dp_balance',10000,'VND','fraudulent',
                        'WON','WITHDRAWN',?::timestamptz,?::timestamptz,
                        ?::timestamptz,'evt_balance')
                """, "2026-09-24T10:00:00Z", "2026-09-25T09:00:00Z",
                "2026-09-24T10:00:00Z");
        AtomicInteger sequence = new AtomicInteger();
        IdGeneratorPort ids = new IdGeneratorPort() {
            @Override public String nextPaymentTransactionId() { return "unused"; }
            @Override public String nextProviderOperationId() { return "unused"; }
            @Override public String nextReviewCaseId() { return "unused"; }
            @Override public String nextFinancialJournalId() {
                return "FJN_REINSTATE_" + sequence.incrementAndGet();
            }
            @Override public UUID nextEventId() { return UUID.randomUUID(); }
        };
        ProviderBalancePort provider = (from, to, cursor, limit) -> new ProviderBalancePort.Page(
                List.of(new ProviderBalancePort.Entry("txn_reinstate", "adjustment", "dp_balance",
                        10000, 0, 10000, "VND", NOW.minusSeconds(60))),
                "txn_reinstate", false);
        BalanceReconciliationService service = new BalanceReconciliationService(
                reconciliation, provider, balanceLedger, ids, () -> NOW,
                JsonMapper.builder().findAndAddModules().build());

        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("DISPUTE_REINSTATEMENT");
        assertThat(jdbc.queryForObject("SELECT disputed_amount_minor FROM reconciliation_runs", Long.class))
                .isEqualTo(10000L);
    }
}

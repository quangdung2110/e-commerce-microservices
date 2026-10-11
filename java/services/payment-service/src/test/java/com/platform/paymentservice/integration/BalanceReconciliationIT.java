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
    void all_pages_are_consumed_and_new_windows_restart_from_the_first_page() {
        var cursors = new java.util.ArrayList<String>();
        ProviderBalancePort provider = (from, to, cursor, limit) -> {
            assertThat(limit).isEqualTo(100);
            cursors.add(cursor);
            if (cursor == null) return new ProviderBalancePort.Page(List.of(new ProviderBalancePort.Entry(
                    "txn_capture", "charge", "ch_balance", 10000, -100, 10100, "VND", NOW)), "txn_capture", true);
            return new ProviderBalancePort.Page(List.of(new ProviderBalancePort.Entry(
                    "txn_payout", "payout", "po_balance", -9000, 0, -9000, "VND", NOW)), "txn_payout", false);
        };
        var service = balanceService(provider);
        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 500)).isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(service.run(NOW.minusSeconds(1800), NOW.plusSeconds(1800), false, 500)).isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(cursors).containsExactly(null, "txn_capture", null, "txn_capture");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT account_code FROM financial_ledger_entries WHERE direction='DEBIT' AND journal_id IN (SELECT id FROM financial_journals WHERE journal_type='PROCESSING_FEE')", String.class)).isEqualTo("PROVIDER_RECEIVABLE");
        assertThat(jdbc.queryForList("SELECT fee_amount_minor FROM reconciliation_runs ORDER BY id", Long.class)).containsExactly(-100L, -100L);
    }

    @Test
    void capture_already_recorded_without_balance_id_is_verified_without_double_posting() {
        var service = balanceService((from, to, cursor, limit) -> new ProviderBalancePort.Page(List.of(
                new ProviderBalancePort.Entry("txn_capture", "charge", "ch_balance", 10000, 0, 10000, "VND", NOW)), "txn_capture", false));
        var lines = "[{\"account_code\":\"PROVIDER_RECEIVABLE\",\"direction\":\"DEBIT\",\"amount_minor\":10000,\"currency\":\"VND\"},{\"account_code\":\"CUSTOMER_PAYMENT_CLEARING\",\"direction\":\"CREDIT\",\"amount_minor\":10000,\"currency\":\"VND\"}]";
        balanceLedger.post(new com.platform.paymentservice.application.port.out.BalanceLedgerPort.Journal(
                "FJN_PRIOR", "TXN_BALANCE", null, null, "CAPTURE", null, "CHARGE", "ch_balance", 10000, "VND", NOW, NOW, lines));
        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 100)).isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);
        var mismatch = balanceService((from, to, cursor, limit) -> new ProviderBalancePort.Page(List.of(
                new ProviderBalancePort.Entry("txn_capture", "charge", "ch_balance", 9999, 0, 9999, "VND", NOW)), "txn_capture", false));
        assertThat(mismatch.run(NOW.minusSeconds(3600), NOW, false, 100)).isEqualTo(ReconciliationService.Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);
    }

    @Test
    void refund_failure_is_a_reversal_with_negative_net_refund_total() {
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,reason,inventory_evidence,status,provider_refund_id,finalized_at)
                VALUES ('RFD_BALANCE','TXN_BALANCE',801,'rfr-balance','refund-balance-key',10000,
                        'ADMIN_FORCE_REFUND_BEFORE_DISPATCH','{}','SUCCESS','re_balance',?::timestamptz)
                """, NOW.toString());
        jdbc.update("UPDATE payment_transactions SET status='REFUNDED'");
        var service = balanceService((from, to, cursor, limit) -> new ProviderBalancePort.Page(List.of(
                new ProviderBalancePort.Entry("txn_reversal", "refund_failure", "re_balance", 10000, 0, 10000, "VND", NOW)), "txn_reversal", false));
        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 100)).isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT refunded_amount_minor FROM reconciliation_runs", Long.class)).isEqualTo(-10000L);
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class)).isEqualTo("REFUND_REVERSAL");
    }

    @Test
    void frozen_balance_run_does_not_write_or_advance_watermark_and_scheduler_can_catch_up() {
        var service = balanceService((from, to, cursor, limit) -> { throw new AssertionError("frozen run must not call Stripe"); });
        assertThat(service.run(NOW.minusSeconds(7200), NOW, true, 100)).isEqualTo(ReconciliationService.Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT error_message FROM reconciliation_runs", String.class)).isEqualTo("AUTO_CORRECTION_FROZEN");
        assertThat(reconciliation.resumeFrom("BALANCE_LEDGER", NOW.minusSeconds(3600))).isEqualTo(NOW.minusSeconds(7200));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
    }

    @Test
    void freeze_enabled_during_stripe_read_blocks_the_next_balance_mutation() {
        var service = balanceService((from, to, cursor, limit) -> {
            reconciliation.setFreeze(true, "finance-ops", "INC-live-freeze", NOW);
            return new ProviderBalancePort.Page(List.of(new ProviderBalancePort.Entry(
                    "txn_freeze", "charge", "ch_balance", 10000, 0, 10000, "VND", NOW)), "txn_freeze", false);
        });
        assertThat(service.run(NOW.minusSeconds(3600), NOW, false, 100)).isEqualTo(ReconciliationService.Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
    }

    private BalanceReconciliationService balanceService(ProviderBalancePort provider) {
        var sequence = new AtomicInteger();
        var ids = new IdGeneratorPort() {
            public String nextPaymentTransactionId() { return "unused"; }
            public String nextProviderOperationId() { return "unused"; }
            public String nextReviewCaseId() { return "unused"; }
            public String nextFinancialJournalId() { return "FJN_PAGE_" + sequence.incrementAndGet(); }
            public UUID nextEventId() { return UUID.randomUUID(); }
        };
        return new BalanceReconciliationService(reconciliation, provider, balanceLedger, ids, () -> NOW,
                JsonMapper.builder().findAndAddModules().build());
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
        String lines = "[{\"account_code\":\"PROVIDER_RECEIVABLE\",\"direction\":\"DEBIT\",\"amount_minor\":10000},{\"account_code\":\"DISPUTE_HOLD_OR_LOSS\",\"direction\":\"CREDIT\",\"amount_minor\":10000}]";
        var fact = new com.platform.paymentservice.application.port.out.CommitBatch.JournalRecord(
                "FJN_WEBHOOK_LATE", "TXN_BALANCE", null, "DSP_BALANCE", "DISPUTE_REINSTATEMENT", null,
                "DISPUTE_REINSTATEMENT", "DSP_BALANCE", null, 10000, "VND", NOW, NOW, lines);
        var change = new com.platform.paymentservice.application.port.out.CommitBatch.DisputeChange(
                "DSP_BALANCE", 0, com.platform.paymentservice.domain.DisputeStatus.WON,
                com.platform.paymentservice.domain.DisputeStatus.WON,
                com.platform.paymentservice.domain.DisputeFundsState.REINSTATED, NOW, "evt_late", null, NOW);
        var history = new com.platform.paymentservice.application.port.out.CommitBatch.HistoryRecord(
                "TXN_BALANCE", null, "DSP_BALANCE", "DISPUTE", "D4", "WON", "WON", 0,
                "WEBHOOK", "evt_late", "system", "{}", NOW);
        commits.commit(new com.platform.paymentservice.application.port.out.CommitBatch(null, null, change,
                List.of(history), List.of(), null, null, null, List.of(fact)));
        assertThat(jdbc.queryForObject("SELECT funds_state FROM payment_disputes", String.class)).isEqualTo("REINSTATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);
    }
}

package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.port.out.CommitBatch;
import com.platform.paymentservice.application.port.out.ConcurrentFinancialMutationException;
import com.platform.paymentservice.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFinancialCommitIT extends AbstractPaymentIT {
    private static final String PAYMENT_ID = "TXN_ATOMIC";

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at)
                VALUES (?,10,1,?,100,'VND','pi_atomic','PENDING',now()+interval '15 minutes')
                """, PAYMENT_ID, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO webhook_logs
                  (stripe_event_id,event_type,event_created_at,livemode,raw_body_sha256,
                   signing_secret_id,verified_at,status,processing_attempts,lease_owner,
                   lease_token,lease_expires_at,received_at)
                VALUES ('evt_atomic','payment_intent.succeeded',now(),false,repeat('a',64),
                        'key-1',now(),'PROCESSING',1,'worker-a',3,now()+interval '1 minute',now())
                """);
    }

    @Test
    void commits_aggregate_history_outbox_and_inbox_terminal_atomically() {
        long inboxId = jdbc.queryForObject("SELECT id FROM webhook_logs WHERE stripe_event_id='evt_atomic'", Long.class);
        commits.commit(successBatch(inboxId, 3));
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, PAYMENT_ID))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_ledger_entries", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE id=?", String.class, inboxId))
                .isEqualTo("PROCESSED");
    }

    @Test
    void stale_inbox_fence_rolls_back_every_earlier_write() {
        long inboxId = jdbc.queryForObject("SELECT id FROM webhook_logs WHERE stripe_event_id='evt_atomic'", Long.class);
        assertThatThrownBy(() -> commits.commit(successBatch(inboxId, 2)))
                .isInstanceOf(ConcurrentFinancialMutationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, PAYMENT_ID))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
    }

    @Test
    void unbalanced_journal_rolls_back_transition_history_outbox_and_inbox() {
        long inboxId = jdbc.queryForObject(
                "SELECT id FROM webhook_logs WHERE stripe_event_id='evt_atomic'", Long.class);
        CommitBatch valid = successBatch(inboxId, 3);
        var invalidJournal = new CommitBatch.JournalRecord(
                "FJN_UNBALANCED", PAYMENT_ID, null, null, "CAPTURE", null,
                "CHARGE_CAPTURE", "ch_atomic", null, 100, "VND", Instant.now(),
                Instant.now(), "[{\"account_code\":\"PROVIDER_RECEIVABLE\",\"direction\":\"DEBIT\",\"amount_minor\":100,\"currency\":\"VND\"},{\"account_code\":\"CUSTOMER_PAYMENT_CLEARING\",\"direction\":\"CREDIT\",\"amount_minor\":90,\"currency\":\"VND\"}]");
        CommitBatch invalid = new CommitBatch(valid.payment(), valid.refund(), valid.dispute(),
                valid.history(), valid.outbox(), valid.reviewCase(), valid.inboxCompletion(),
                valid.disputeCreate(), List.of(invalidJournal));

        assertThatThrownBy(() -> commits.commit(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE id=?",
                String.class, inboxId)).isEqualTo("PROCESSING");
    }

    private CommitBatch successBatch(long inboxId, long token) {
        Instant now = Instant.now();
        var payment = new CommitBatch.PaymentChange(PAYMENT_ID, 0, PaymentStatus.PENDING,
                PaymentStatus.SUCCESS, 1, now, "evt_atomic", null, "ch_atomic", now,
                null, true, false);
        var history = new CommitBatch.HistoryRecord(PAYMENT_ID, null, null, "PAYMENT",
                "T2", "PENDING", "SUCCESS", 1, "WEBHOOK", "evt_atomic", null, "{}", now);
        var outbox = new CommitBatch.OutboxRecord(UUID.randomUUID(), PAYMENT_ID,
                "com.ecommerce.payment.transaction.succeeded.v2", "10", "{}",
                "{\"specversion\":\"1.0\"}", now);
        var inbox = new CommitBatch.InboxCompletion(inboxId, token, "PROCESSED", null, now);
        var journal = new CommitBatch.JournalRecord(
                "FJN_ATOMIC", PAYMENT_ID, null, null, "CAPTURE", null,
                "CHARGE_CAPTURE", "ch_atomic", null, 100, "VND", now, now,
                "[{\"account_code\":\"PROVIDER_RECEIVABLE\",\"direction\":\"DEBIT\",\"amount_minor\":100,\"currency\":\"VND\"},{\"account_code\":\"CUSTOMER_PAYMENT_CLEARING\",\"direction\":\"CREDIT\",\"amount_minor\":100,\"currency\":\"VND\"}]");
        return new CommitBatch(payment, null, null, List.of(history), List.of(outbox), null,
                inbox, null, List.of(journal));
    }
}

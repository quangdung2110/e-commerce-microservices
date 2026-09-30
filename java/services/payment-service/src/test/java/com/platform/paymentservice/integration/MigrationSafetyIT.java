package com.platform.paymentservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationSafetyIT extends AbstractPaymentIT {
    @Test
    void creates_exactly_the_twelve_canonical_tables() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name IN
                 ('payment_transactions','refund_transactions','payment_disputes','provider_operations',
                  'webhook_logs','payment_status_history','outbox_events','payment_idempotency',
                  'payment_review_cases','reconciliation_runs','financial_journals','financial_ledger_entries')
                """, Integer.class);
        assertThat(count).isEqualTo(12);
    }

    @Test
    void enforces_one_lifetime_refund_and_amount_not_above_payment() {
        insertSuccessfulPayment("TXN_A", 1, 1, 1_000);
        insertRefund("RFD_A", "TXN_A", 1, 500, "key-a");
        assertThatThrownBy(() -> insertRefund("RFD_B", "TXN_A", 1, 500, "key-b"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertSuccessfulPayment("TXN_C", 2, 1, 1_000);
        assertThatThrownBy(() -> insertRefund("RFD_C", "TXN_C", 2, 1_001, "key-c"))
                .isInstanceOfSatisfying(DataAccessException.class, exception ->
                        assertThat(exception.getRootCause())
                                .hasMessageContaining("refund amount exceeds captured payment amount"));
    }

    @Test
    void accepts_balanced_journal_and_rejects_mutation_or_imbalance_at_commit() throws Exception {
        jdbc.update("""
                CALL post_financial_journal(?,NULL,NULL,NULL,'PROCESSING_FEE',?,
                  'BALANCE_TRANSACTION',?,NULL,100,'VND',?,?,CAST(? AS jsonb))
                """, "FJN_A", "txn_balance_a", "txn_balance_a", Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                "[{\"account_code\":\"PROCESSING_FEE_EXPENSE\",\"direction\":\"DEBIT\",\"amount_minor\":100}," +
                "{\"account_code\":\"PROVIDER_RECEIVABLE\",\"direction\":\"CREDIT\",\"amount_minor\":100}]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_ledger_entries WHERE journal_id='FJN_A'", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE financial_journals SET amount_minor=101 WHERE id='FJN_A'"))
                .isInstanceOfSatisfying(DataAccessException.class, exception ->
                        assertThat(exception.getRootCause())
                                .hasMessageContaining("financial_journals is append-only"));

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("""
                    INSERT INTO financial_journals
                      (id,journal_type,source_type,source_id,amount_minor,currency,occurred_at,provider_observed_at)
                    VALUES ('FJN_BAD','CAPTURE','TEST','bad',100,'VND',now(),now())
                    """);
            connection.createStatement().execute("""
                    INSERT INTO financial_ledger_entries
                      (journal_id,line_no,account_code,direction,amount_minor,currency)
                    VALUES ('FJN_BAD',1,'PROVIDER_RECEIVABLE','DEBIT',100,'VND'),
                           ('FJN_BAD',2,'CUSTOMER_PAYMENT_CLEARING','CREDIT',99,'VND')
                    """);
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
        }
    }

    private void insertSuccessfulPayment(String id, long orderId, int attemptNumber, long amount) {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at,succeeded_at)
                VALUES (?,?,?,?,?, 'VND',?,'SUCCESS',now()+interval '15 minutes',now())
                """, id, orderId, attemptNumber, UUID.randomUUID(), amount, "pi_" + id);
    }

    private void insertRefund(String id, String paymentId, long orderId, long amount, String key) {
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,currency,reason,inventory_evidence,status)
                VALUES (?,?,?,?,?,?,'VND','ADMIN_FORCE_REFUND_BEFORE_DISPATCH',
                        '{"type":"COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH","release_reference":"rel"}'::jsonb,'PENDING')
                """, id, paymentId, orderId, "request-" + id, key, amount);
    }
}

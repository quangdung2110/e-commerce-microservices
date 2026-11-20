package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.PaymentQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public final class JdbcPaymentQueryAdapter implements PaymentQueryPort {
    private final JdbcClient jdbc;
    private final org.springframework.transaction.support.TransactionTemplate snapshots;

    public JdbcPaymentQueryAdapter(JdbcClient jdbc, org.springframework.transaction.support.TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.snapshots = new org.springframework.transaction.support.TransactionTemplate(transactions.getTransactionManager());
        snapshots.setReadOnly(true);
        snapshots.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<PaymentView> findPayment(String transactionId) {
        return snapshots.execute(tx -> jdbc.sql("""
                SELECT id, order_id, payment_attempt_number, amount_minor, currency, status,
                       financial_state_version, requires_manual_review, succeeded_at, terminal_at
                  FROM payment_transactions WHERE id = :id
                """).param("id", transactionId).query((rs, ignored) -> new PaymentView(
                        rs.getString("id"), rs.getLong("order_id"), rs.getInt("payment_attempt_number"),
                        rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("status"),
                        rs.getLong("financial_state_version"), rs.getBoolean("requires_manual_review"),
                        instant(rs.getTimestamp("succeeded_at")), instant(rs.getTimestamp("terminal_at")),
                        refundOf(transactionId), disputesOf(transactionId))).optional());
    }

    @Override
    public Optional<RefundView> findRefund(String refundTransactionId) {
        return jdbc.sql("""
                SELECT id, payment_transaction_id, order_id, refund_amount_minor, currency, status
                  FROM refund_transactions WHERE id = :id
                """).param("id", refundTransactionId).query(JdbcPaymentQueryAdapter::refund).optional();
    }

    private RefundView refundOf(String paymentTransactionId) {
        return jdbc.sql("""
                SELECT id, payment_transaction_id, order_id, refund_amount_minor, currency, status
                  FROM refund_transactions WHERE payment_transaction_id = :id
                """).param("id", paymentTransactionId).query(JdbcPaymentQueryAdapter::refund).optional().orElse(null);
    }

    private List<DisputeView> disputesOf(String paymentTransactionId) {
        return jdbc.sql("""
                SELECT id, payment_transaction_id, order_id, amount_minor, currency, status, funds_state,
                       reason_code, evidence_due_at, opened_at, closed_at
                  FROM payment_disputes WHERE payment_transaction_id = :id ORDER BY opened_at, id
                """).param("id", paymentTransactionId).query((rs, ignored) -> new DisputeView(
                        rs.getString("id"), rs.getString("payment_transaction_id"), rs.getLong("order_id"),
                        rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("status"),
                        rs.getString("funds_state"), rs.getString("reason_code"),
                        instant(rs.getTimestamp("evidence_due_at")), rs.getTimestamp("opened_at").toInstant(),
                        instant(rs.getTimestamp("closed_at")))).list();
    }

    private static RefundView refund(ResultSet rs, int ignored) throws SQLException {
        return new RefundView(rs.getString("id"), rs.getString("payment_transaction_id"), rs.getLong("order_id"),
                rs.getLong("refund_amount_minor"), rs.getString("currency").trim(), rs.getString("status"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

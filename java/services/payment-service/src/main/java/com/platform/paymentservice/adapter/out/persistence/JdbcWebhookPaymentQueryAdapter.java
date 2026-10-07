package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.WebhookPaymentQueryPort;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.ProviderCheckpoint;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.DisputeStatus;
import com.platform.paymentservice.domain.DisputeFundsState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Component
public final class JdbcWebhookPaymentQueryAdapter implements WebhookPaymentQueryPort {
    private static final String SELECT = """
            SELECT id,order_id,payment_attempt_number,amount_minor,currency,status,
                   financial_state_version,version,provider_transaction_id,provider_charge_id,last_error_code,
                   last_provider_event_created_at,last_provider_event_id
              FROM payment_transactions
            """;
    private final JdbcClient jdbc;

    public JdbcWebhookPaymentQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PaymentSnapshot> findByProviderTransactionId(String providerTransactionId) {
        return jdbc.sql(SELECT + " WHERE provider_transaction_id=:value")
                .param("value", providerTransactionId).query(this::map).optional();
    }

    @Override
    public Optional<PaymentSnapshot> findByProviderChargeId(String providerChargeId) {
        return jdbc.sql(SELECT + " WHERE provider_charge_id=:value")
                .param("value", providerChargeId).query(this::map).optional();
    }

    @Override
    public Optional<PaymentSnapshot> findById(String paymentTransactionId) {
        return jdbc.sql(SELECT + " WHERE id=:value")
                .param("value", paymentTransactionId).query(this::map).optional();
    }

    @Override
    public Optional<String> findCancelOperationReason(String paymentTransactionId) {
        return jdbc.sql("""
                SELECT request_fingerprint->>'reason' FROM provider_operations
                 WHERE payment_transaction_id=:payment_id AND operation_type='CANCEL'
                """).param("payment_id", paymentTransactionId).query(String.class).optional();
    }

    @Override
    public Optional<RefundSnapshot> findRefundByProviderRefundId(String providerRefundId) {
        return refund(" WHERE r.provider_refund_id=:value", providerRefundId);
    }

    @Override
    public Optional<RefundSnapshot> findRefundById(String refundTransactionId) {
        return refund(" WHERE r.id = :value", refundTransactionId);
    }

    @Override
    public Optional<RefundSnapshot> findRefundByPaymentId(String paymentTransactionId) {
        return refund(" WHERE r.payment_transaction_id=:value", paymentTransactionId);
    }

    @Override
    public Optional<DisputeSnapshot> findDisputeByProviderDisputeId(String providerDisputeId) {
        return dispute(" WHERE d.provider_dispute_id=:value", providerDisputeId);
    }

    @Override
    public Optional<DisputeSnapshot> findDisputeById(String disputeTransactionId) {
        return dispute(" WHERE d.id=:value", disputeTransactionId);
    }

    private Optional<DisputeSnapshot> dispute(String where, String value) {
        return jdbc.sql("""
                SELECT d.id,d.payment_transaction_id,d.order_id,d.amount_minor,d.currency,
                       d.reason_code,d.status,d.funds_state,d.version,d.evidence_due_at,
                       d.last_provider_event_created_at,d.last_provider_event_id,
                       p.payment_attempt_number,p.status AS payment_status,
                       p.version AS payment_version,p.financial_state_version,p.provider_charge_id
                  FROM payment_disputes d JOIN payment_transactions p
                    ON p.id=d.payment_transaction_id
                """ + where).param("value", value).query(this::mapDispute).optional();
    }

    private Optional<RefundSnapshot> refund(String where, String value) {
        return jdbc.sql("""
                SELECT r.id,r.payment_transaction_id,r.order_id,r.refund_amount_minor,r.currency,
                       r.status,r.version,r.provider_refund_id,
                       r.last_provider_event_created_at,r.last_provider_event_id,
                       p.payment_attempt_number,p.status AS payment_status,
                       p.version AS payment_version,p.financial_state_version,
                       p.provider_transaction_id,p.provider_charge_id
                  FROM refund_transactions r JOIN payment_transactions p
                    ON p.id=r.payment_transaction_id
                """ + where).param("value", value).query(this::mapRefund).optional();
    }

    private RefundSnapshot mapRefund(ResultSet rs, int row) throws SQLException {
        return new RefundSnapshot(rs.getString("id"), rs.getString("payment_transaction_id"),
                rs.getLong("order_id"), rs.getInt("payment_attempt_number"),
                rs.getLong("refund_amount_minor"), rs.getString("currency"),
                RefundStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                rs.getString("provider_refund_id"), checkpoint(rs),
                PaymentStatus.valueOf(rs.getString("payment_status")), rs.getInt("payment_version"),
                rs.getLong("financial_state_version"), rs.getString("provider_transaction_id"),
                rs.getString("provider_charge_id"));
    }

    private DisputeSnapshot mapDispute(ResultSet rs, int row) throws SQLException {
        var due = rs.getTimestamp("evidence_due_at");
        return new DisputeSnapshot(rs.getString("id"), rs.getString("payment_transaction_id"),
                rs.getLong("order_id"), rs.getInt("payment_attempt_number"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("reason_code"),
                DisputeStatus.valueOf(rs.getString("status")),
                DisputeFundsState.valueOf(rs.getString("funds_state")), rs.getInt("version"),
                due == null ? null : due.toInstant(), checkpoint(rs),
                PaymentStatus.valueOf(rs.getString("payment_status")), rs.getInt("payment_version"),
                rs.getLong("financial_state_version"), rs.getString("provider_charge_id"));
    }

    private static ProviderCheckpoint checkpoint(ResultSet rs) throws SQLException {
        var checkpointTime = rs.getTimestamp("last_provider_event_created_at");
        String checkpointId = rs.getString("last_provider_event_id");
        return checkpointTime == null || checkpointId == null ? null
                : new ProviderCheckpoint(checkpointTime.toInstant(), checkpointId);
    }

    private PaymentSnapshot map(ResultSet rs, int row) throws SQLException {
        ProviderCheckpoint checkpoint = checkpoint(rs);
        return new PaymentSnapshot(rs.getString("id"), rs.getLong("order_id"),
                rs.getInt("payment_attempt_number"), rs.getLong("amount_minor"),
                rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getLong("financial_state_version"), rs.getInt("version"),
                rs.getString("provider_transaction_id"), rs.getString("provider_charge_id"),
                rs.getString("last_error_code"), checkpoint);
    }
}

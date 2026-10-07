package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.RefundPollingPort;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public final class JdbcRefundPollingAdapter implements RefundPollingPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcRefundPollingAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<DueRefund> findDue(Instant updatedBefore, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return jdbc.sql("""
                SELECT r.id,r.payment_transaction_id,r.order_id,r.refund_amount_minor,
                       r.currency,r.status,r.version,r.provider_refund_id,
                       p.payment_attempt_number,p.status AS payment_status,p.version AS payment_version,
                       p.financial_state_version
                  FROM refund_transactions r
                  JOIN payment_transactions p ON p.id=r.payment_transaction_id
                 WHERE r.status='PROCESSING' AND r.updated_at < :cutoff
                   AND r.provider_refund_id IS NOT NULL
                 ORDER BY r.updated_at,r.id
                 LIMIT :limit
                """).param("cutoff", Timestamp.from(updatedBefore)).param("limit", limit)
                .query((rs, row) -> new DueRefund(rs.getString("id"),
                        rs.getString("payment_transaction_id"), rs.getLong("order_id"),
                        rs.getInt("payment_attempt_number"), rs.getLong("refund_amount_minor"),
                        rs.getString("currency"), RefundStatus.valueOf(rs.getString("status")),
                        rs.getInt("version"), rs.getString("provider_refund_id"),
                        PaymentStatus.valueOf(rs.getString("payment_status")),
                        rs.getInt("payment_version"), rs.getLong("financial_state_version")))
                .list();
    }

    @Override
    public boolean recordObservation(String refundId, int expectedVersion,
                                     String providerStatus, Instant observedAt) {
        return jdbc.sql("""
                UPDATE refund_transactions
                   SET provider_status_snapshot=:provider_status,
                       provider_synced_at=:observed_at,version=version+1,
                       updated_at=updated_at
                 WHERE id=:id AND version=:expected_version AND status='PROCESSING'
                """).param("provider_status", providerStatus)
                .param("observed_at", Timestamp.from(observedAt)).param("id", refundId)
                .param("expected_version", expectedVersion).update() == 1;
    }

    @Override
    public boolean recordReview(DueRefund refund, String reasonCode, String evidenceJson,
                                String reviewCaseId, Instant observedAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changedRefund = jdbc.sql("""
                    UPDATE refund_transactions
                       SET provider_synced_at=:now,version=version+1
                     WHERE id=:id AND version=:version AND status='PROCESSING'
                    """).param("now", Timestamp.from(observedAt)).param("id", refund.id())
                    .param("version", refund.version()).update();
            int changedPayment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET requires_manual_review=TRUE,version=version+1,updated_at=:now
                     WHERE id=:id AND version=:version
                    """).param("now", Timestamp.from(observedAt)).param("id", refund.paymentId())
                    .param("version", refund.paymentVersion()).update();
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,refund_transaction_id,reason_code,
                       case_status,evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:refund_id,:reason,'OPEN',CAST(:evidence AS jsonb),
                            'SCHEDULER',:now)
                    ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                    DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                    """).param("id", reviewCaseId).param("payment_id", refund.paymentId())
                    .param("refund_id", refund.id()).param("reason", reasonCode)
                    .param("evidence", evidenceJson).param("now", Timestamp.from(observedAt)).update();
            if (changedRefund != 1 || changedPayment != 1 || review != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }
}

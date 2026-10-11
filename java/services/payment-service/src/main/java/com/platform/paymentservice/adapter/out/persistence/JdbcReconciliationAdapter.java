package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.ReconciliationPort;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.DisputeFundsState;
import com.platform.paymentservice.domain.DisputeStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Component
public final class JdbcReconciliationAdapter implements ReconciliationPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Map<Long, Connection> owners = new ConcurrentHashMap<>();

    public JdbcReconciliationAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<Run> start(String lane, Instant windowFrom, Instant windowTo,
                               boolean frozen, Instant now) {
        Connection owner = acquire(lane);
        if (owner == null) return Optional.empty();
        try {
            Run run = transactions.execute(tx -> {
                // The exclusive session lock proves any previous RUNNING row has lost its owner.
                // Taking its row lock also waits for an already fenced mutation to finish.
                jdbc.sql("""
                        UPDATE reconciliation_runs SET status='FAILED',finished_at=:now,
                               error_message='OWNER_SESSION_LOST'
                         WHERE lane=:lane AND status='RUNNING'
                        """).param("lane", lane).param("now", timestamp(now)).update();
                Long id = jdbc.sql("""
                    INSERT INTO reconciliation_runs
                      (lane,window_from,window_to,provider_cursor_from,status,
                       auto_correction_frozen,started_at)
                    VALUES (:lane,:window_from,:window_to,
                            (SELECT provider_cursor_to FROM reconciliation_runs
                              WHERE lane=:lane AND status='COMPLETED'
                              ORDER BY finished_at DESC LIMIT 1),
                            'RUNNING',:frozen,:now)
                    RETURNING id
                    """).param("lane", lane).param("window_from", timestamp(windowFrom))
                    .param("window_to", timestamp(windowTo)).param("frozen", frozen)
                    .param("now", timestamp(now)).query(Long.class).single();
            String cursor = jdbc.sql("SELECT provider_cursor_from FROM reconciliation_runs WHERE id=:id")
                    .param("id", id).query(String.class).optional().orElse(null);
                return new Run(id, lane, windowFrom, windowTo, cursor, frozen);
            });
            owners.put(run.id(), owner);
            return Optional.of(run);
        } catch (RuntimeException failure) {
            release(owner);
            throw failure;
        }
    }

    private Connection acquire(String lane) {
        int key = switch (lane) {
            case "OBJECT_STATE" -> 1;
            case "BALANCE_LEDGER" -> 2;
            default -> throw new IllegalArgumentException("invalid reconciliation lane");
        };
        Connection connection = null;
        try {
            var manager = (DataSourceTransactionManager) transactions.getTransactionManager();
            connection = manager.getDataSource().getConnection();
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(731024, ?)")) {
                statement.setInt(1, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) return connection;
                }
            }
            connection.close();
            return null;
        } catch (SQLException failure) {
            release(connection);
            throw new IllegalStateException("cannot acquire reconciliation ownership", failure);
        }
    }

    @Override
    public <T> T owned(long runId, Supplier<T> mutation) {
        return transactions.execute(tx -> {
            boolean active = jdbc.sql("SELECT status FROM reconciliation_runs WHERE id=:id FOR UPDATE")
                    .param("id", runId).query(String.class).optional().filter("RUNNING"::equals).isPresent();
            Connection owner = owners.get(runId);
            try {
                if (!active || owner == null || !owner.isValid(2))
                    throw new IllegalStateException("reconciliation ownership lost");
            } catch (SQLException lost) { throw new IllegalStateException("reconciliation ownership lost", lost); }
            return mutation.get();
        });
    }

    private void release(Connection owner) {
        if (owner == null) return;
        try {
            // Explicitly unlock before returning a live session to the pool.
            try (var statement = owner.createStatement()) { statement.execute("SELECT pg_advisory_unlock_all()"); }
        } catch (SQLException ignored) {
            try { owner.abort(Runnable::run); } catch (SQLException alsoLost) { /* session is gone */ }
        } finally {
            try { owner.close(); } catch (SQLException ignored) { /* session is gone */ }
        }
    }

    @Override
    public boolean currentFreeze(boolean fallback) {
        return jdbc.sql("""
                SELECT auto_correction_frozen FROM reconciliation_runs
                 WHERE lane='CONTROL' AND status='COMPLETED'
                 ORDER BY finished_at DESC,id DESC LIMIT 1
                """).query(Boolean.class).optional().orElse(fallback);
    }

    @Override
    public Instant resumeFrom(String lane, Instant requestedFrom) {
        return jdbc.sql("""
                SELECT LEAST(CAST(:requested AS timestamptz),
                       (SELECT window_to FROM reconciliation_runs WHERE lane=:lane AND status='COMPLETED'
                         ORDER BY finished_at DESC,id DESC LIMIT 1),
                       (SELECT min(window_from) FROM reconciliation_runs WHERE lane=:lane AND status='FAILED'
                         AND started_at>=COALESCE((SELECT max(finished_at) FROM reconciliation_runs
                              WHERE lane=:lane AND status='COMPLETED'),'-infinity'::timestamptz)))
                """).param("requested", timestamp(requestedFrom)).param("lane", lane)
                .query(Timestamp.class).single().toInstant();
    }

    @Override
    public void setFreeze(boolean frozen, String actor, String causeRef, Instant now) {
        jdbc.sql("""
                INSERT INTO reconciliation_runs
                  (lane,window_from,window_to,status,auto_correction_frozen,
                   requested_by,cause_ref,started_at,finished_at)
                VALUES ('CONTROL',:window_from,:window_to,'COMPLETED',:frozen,
                        :actor,:cause_ref,:now,:now)
                """).param("window_from", timestamp(now.minusNanos(1_000)))
                .param("window_to", timestamp(now)).param("frozen", frozen)
                .param("actor", actor).param("cause_ref", causeRef)
                .param("now", timestamp(now)).update();
    }

    @Override
    public List<PaymentCandidate> findPayments(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                SELECT id,order_id,payment_attempt_number,amount_minor,currency,status,version,
                       financial_state_version,provider_transaction_id,provider_charge_id
                  FROM payment_transactions
                 WHERE provider_transaction_id IS NOT NULL
                   AND ((updated_at>=:from AND updated_at<:to)
                     OR status IN ('PENDING','PROCESSING','REFUNDING'))
                 ORDER BY updated_at,id LIMIT :limit
                """).param("from", timestamp(from)).param("to", timestamp(to)).param("limit", limit)
                .query((rs, ignored) -> new PaymentCandidate(rs.getString("id"),
                        rs.getLong("order_id"), rs.getInt("payment_attempt_number"),
                        rs.getLong("amount_minor"), rs.getString("currency"),
                        PaymentStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                        rs.getLong("financial_state_version"), rs.getString("provider_transaction_id"),
                        rs.getString("provider_charge_id"))).list();
    }

    @Override
    public List<RefundCandidate> findRefunds(Instant from, Instant to, Instant successSince,
                                             int limit) {
        return jdbc.sql("""
                SELECT r.id,r.payment_transaction_id,r.order_id,p.payment_attempt_number,
                       r.refund_amount_minor,r.currency,r.status,r.version,r.provider_refund_id,
                       p.status AS payment_status,p.version AS payment_version,p.financial_state_version
                  FROM refund_transactions r JOIN payment_transactions p ON p.id=r.payment_transaction_id
                 WHERE r.provider_refund_id IS NOT NULL
                   AND ((r.updated_at>=:from AND r.updated_at<:to)
                        OR r.status = 'PROCESSING'
                        OR (r.status = 'SUCCESS'
                            AND COALESCE(r.finalized_at, r.updated_at) >= :successSince))
                 ORDER BY r.provider_synced_at NULLS FIRST, r.id LIMIT :limit
                """).param("from", timestamp(from)).param("to", timestamp(to))
                .param("successSince", timestamp(successSince)).param("limit", limit)
                .query((rs, ignored) -> new RefundCandidate(rs.getString("id"),
                        rs.getString("payment_transaction_id"), rs.getLong("order_id"),
                        rs.getInt("payment_attempt_number"), rs.getLong("refund_amount_minor"),
                        rs.getString("currency"), RefundStatus.valueOf(rs.getString("status")),
                        rs.getInt("version"), rs.getString("provider_refund_id"),
                        PaymentStatus.valueOf(rs.getString("payment_status")),
                        rs.getInt("payment_version"), rs.getLong("financial_state_version"))).list();
    }

    @Override
    public List<DisputeCandidate> findDisputes(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                SELECT d.id,d.payment_transaction_id,d.order_id,p.payment_attempt_number,
                       d.amount_minor,d.currency,d.reason_code,d.status,d.funds_state,d.version,
                       d.provider_dispute_id,d.evidence_due_at,p.status AS payment_status,
                       p.version AS payment_version,p.financial_state_version
                  FROM payment_disputes d JOIN payment_transactions p ON p.id=d.payment_transaction_id
                 WHERE (d.updated_at>=:from AND d.updated_at<:to)
                    OR d.status IN ('OPEN','UNDER_REVIEW')
                 ORDER BY d.updated_at,d.id LIMIT :limit
                """).param("from", timestamp(from)).param("to", timestamp(to)).param("limit", limit)
                .query((rs, ignored) -> new DisputeCandidate(rs.getString("id"),
                        rs.getString("payment_transaction_id"), rs.getLong("order_id"),
                        rs.getInt("payment_attempt_number"), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("reason_code"),
                        DisputeStatus.valueOf(rs.getString("status")),
                        DisputeFundsState.valueOf(rs.getString("funds_state")), rs.getInt("version"),
                        rs.getString("provider_dispute_id"),
                        rs.getTimestamp("evidence_due_at") == null ? null
                                : rs.getTimestamp("evidence_due_at").toInstant(),
                        PaymentStatus.valueOf(rs.getString("payment_status")),
                        rs.getInt("payment_version"), rs.getLong("financial_state_version"))).list();
    }

    @Override
    public boolean recordPaymentObservation(String id, int version, String providerStatus, Instant at) {
        return jdbc.sql("""
                UPDATE payment_transactions SET provider_status_snapshot=:status,
                       provider_synced_at=:at,version=version+1,updated_at=:at
                 WHERE id=:id AND version=:version
                """).param("status", providerStatus).param("at", timestamp(at))
                .param("id", id).param("version", version).update() == 1;
    }

    @Override
    public boolean recordRefundObservation(String id, int version, String providerStatus, Instant at) {
        return jdbc.sql("""
                UPDATE refund_transactions SET provider_status_snapshot=:status,
                       provider_synced_at=:at,version=version+1,updated_at=:at
                 WHERE id=:id AND version=:version
                """).param("status", providerStatus).param("at", timestamp(at))
                .param("id", id).param("version", version).update() == 1;
    }

    @Override
    public boolean recordDisputeObservation(String id, int version, Instant at) {
        return jdbc.sql("UPDATE payment_disputes SET version=version+1,updated_at=:at WHERE id=:id AND version=:version")
                .param("at", timestamp(at)).param("id", id).param("version", version).update() == 1;
    }

    @Override
    public boolean openDrift(String paymentId, String refundId, String reviewCaseId,
                             String evidenceJson, Instant openedAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int payment = jdbc.sql("""
                    UPDATE payment_transactions SET requires_manual_review=TRUE,
                           version=version+1,updated_at=:now WHERE id=:id
                    """).param("now", timestamp(openedAt)).param("id", paymentId).update();
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,refund_transaction_id,reason_code,
                       case_status,evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:refund_id,'RECONCILIATION_DRIFT','OPEN',
                            CAST(:evidence AS jsonb),'RECONCILIATION',:now)
                    ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                    DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                    """).param("id", reviewCaseId).param("payment_id", paymentId)
                    .param("refund_id", refundId).param("evidence", evidenceJson)
                    .param("now", timestamp(openedAt)).update();
            if (payment != 1 || review != 1) status.setRollbackOnly();
            return payment == 1 && review == 1;
        }));
    }

    @Override
    public void complete(long runId, String cursor, Counts c, Instant finishedAt) {
        try { owned(runId, () -> {
        int changed = jdbc.sql("""
                UPDATE reconciliation_runs SET status='COMPLETED',provider_cursor_to=:cursor,
                       scanned_count=:scanned,drift_count=:drift,corrected_count=:corrected,
                       review_opened_count=:reviews,captured_amount_minor=:captured,
                       refunded_amount_minor=:refunded,disputed_amount_minor=:disputed,
                       fee_amount_minor=:fees,payout_amount_minor=:payouts,finished_at=:finished
                 WHERE id=:id AND status='RUNNING'
                """).param("cursor", cursor).param("scanned", c.scanned()).param("drift", c.drift())
                .param("corrected", c.corrected()).param("reviews", c.reviewOpened())
                .param("captured", c.capturedAmountMinor()).param("refunded", c.refundedAmountMinor())
                .param("disputed", c.disputedAmountMinor()).param("fees", c.feeAmountMinor())
                .param("payouts", c.payoutAmountMinor()).param("finished", timestamp(finishedAt))
                .param("id", runId).update();
        if (changed != 1) throw new IllegalStateException("reconciliation run is not active");
        return null;
        }); } finally { release(owners.remove(runId)); }
    }

    @Override
    public void fail(long runId, String error, Instant finishedAt) {
        String safe = error == null ? "UNKNOWN" : error.substring(0, Math.min(error.length(), 500));
        try {
            // A fenced predecessor must never change the successor's run.
            Connection owner = owners.get(runId);
            if (owner == null || !owner.isValid(2)) return;
            owned(runId, () -> {
        jdbc.sql("""
                UPDATE reconciliation_runs SET status='FAILED',error_message=:error,finished_at=:finished
                 WHERE id=:id AND status='RUNNING'
                """).param("error", safe).param("finished", timestamp(finishedAt))
                .param("id", runId).update();
            return null;
            });
        } catch (SQLException | IllegalStateException lost) {
            // The next lock holder records OWNER_SESSION_LOST for an abandoned run.
        } finally { release(owners.remove(runId)); }
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
}

package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.RetentionPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
public final class JdbcRetentionAdapter implements RetentionPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcRetentionAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Result trim(Policy policy, Instant now, int batchSize) {
        return transactions.execute(ignored -> {
            jdbc.sql("SET LOCAL statement_timeout = '60s'").update();
            boolean leader = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('payment-retention-trimmer'))")
                    .query(Boolean.class).single();
            if (!leader) return new Result(0, 0, 0, 0, 0, 0, 0, false);
            jdbc.sql("SET LOCAL payment.retention_mode = 'on'").update();
            int secrets = shredClientSecrets(now, batchSize);
            int raw = shredWebhookPayloads(now.minus(policy.webhookRawDays(), ChronoUnit.DAYS), batchSize);
            int metadata = deleteWebhookMetadata(monthsBefore(now, policy.webhookMetadataMonths()), batchSize);
            int idempotency = deleteExpiredIdempotency(now, batchSize);
            int reconciliation = deleteReconciliationRuns(
                    monthsBefore(now, policy.reconciliationRunMonths()), batchSize);
            int financial = deleteFinancialPayments(
                    yearsBefore(now, policy.financialYears()), now, batchSize);
            Watermark watermark = outboxWatermark(policy.outboxSlotName());
            int outbox = watermark.available()
                    ? deleteOutbox(now.minus(policy.outboxDays(), ChronoUnit.DAYS), watermark.lsn(), batchSize)
                    : 0;
            return new Result(secrets, raw, metadata, idempotency, reconciliation,
                    financial, outbox, watermark.available());
        });
    }

    private int shredClientSecrets(Instant now, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT id FROM payment_transactions
                     WHERE client_secret_ciphertext IS NOT NULL
                       AND (status <> 'PENDING' OR client_expires_at<=:now)
                     ORDER BY client_expires_at NULLS FIRST LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE payment_transactions p
                   SET client_secret_ciphertext=NULL,client_secret_key_id=NULL,updated_at=:now
                  FROM candidates c WHERE p.id=c.id
                """).param("now", timestamp(now)).param("limit", limit).update();
    }

    private int shredWebhookPayloads(Instant cutoff, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT w.id FROM webhook_logs w
                    LEFT JOIN payment_transactions p ON p.id=w.payment_transaction_id
                     WHERE w.raw_body_ciphertext IS NOT NULL
                       AND w.status IN ('PROCESSED','IGNORED','DEAD')
                       AND w.received_at<:cutoff
                       AND (p.id IS NULL OR p.legal_hold_until IS NULL OR p.legal_hold_until<=now())
                     ORDER BY w.received_at LIMIT :limit FOR UPDATE OF w SKIP LOCKED
                )
                UPDATE webhook_logs w
                   SET raw_body_ciphertext=NULL,raw_body_key_id=NULL,
                       stripe_signature_ciphertext=NULL
                  FROM candidates c WHERE w.id=c.id
                """).param("cutoff", timestamp(cutoff)).param("limit", limit).update();
    }

    private int deleteWebhookMetadata(Instant cutoff, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT w.id FROM webhook_logs w
                    LEFT JOIN payment_transactions p ON p.id=w.payment_transaction_id
                     WHERE w.status IN ('PROCESSED','IGNORED','DEAD')
                       AND w.received_at<:cutoff
                       AND (p.id IS NULL OR p.legal_hold_until IS NULL OR p.legal_hold_until<=now())
                       AND NOT EXISTS (
                           SELECT 1 FROM payment_review_cases c
                            WHERE c.payment_transaction_id=w.payment_transaction_id
                              AND c.case_status IN ('OPEN','IN_PROGRESS'))
                     ORDER BY w.received_at LIMIT :limit FOR UPDATE OF w SKIP LOCKED
                )
                DELETE FROM webhook_logs w USING candidates c WHERE w.id=c.id
                """).param("cutoff", timestamp(cutoff)).param("limit", limit).update();
    }

    private int deleteExpiredIdempotency(Instant now, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT idempotency_key,action FROM payment_idempotency
                     WHERE expires_at<:now ORDER BY expires_at LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM payment_idempotency i USING candidates c
                 WHERE i.idempotency_key=c.idempotency_key AND i.action=c.action
                """).param("now", timestamp(now)).param("limit", limit).update();
    }

    private int deleteReconciliationRuns(Instant cutoff, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT id FROM reconciliation_runs
                     WHERE status IN ('COMPLETED','FAILED') AND finished_at<:cutoff
                     ORDER BY finished_at LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                DELETE FROM reconciliation_runs r USING candidates c WHERE r.id=c.id
                """).param("cutoff", timestamp(cutoff)).param("limit", limit).update();
    }

    private int deleteFinancialPayments(Instant cutoff, Instant now, int limit) {
        List<String> ids = jdbc.sql("""
                SELECT p.id FROM payment_transactions p
                 WHERE p.status IN ('SUCCESS','FAILED','EXPIRED','CANCELED','REFUNDED')
                   AND p.financial_export_manifest_id IS NOT NULL
                   AND (p.legal_hold_until IS NULL OR p.legal_hold_until<=:now)
                   AND NOT EXISTS (SELECT 1 FROM payment_review_cases c
                                    WHERE c.payment_transaction_id=p.id
                                      AND c.case_status IN ('OPEN','IN_PROGRESS'))
                   AND NOT EXISTS (SELECT 1 FROM payment_disputes d
                                    WHERE d.payment_transaction_id=p.id
                                      AND d.status IN ('OPEN','UNDER_REVIEW'))
                   AND GREATEST(COALESCE(p.terminal_at,p.succeeded_at,p.updated_at),
                       COALESCE((SELECT max(GREATEST(COALESCE(r.reversed_at,r.updated_at),
                                                     COALESCE(r.finalized_at,r.updated_at)))
                                   FROM refund_transactions r
                                  WHERE r.payment_transaction_id=p.id),p.updated_at),
                       COALESCE((SELECT max(d.closed_at) FROM payment_disputes d
                                  WHERE d.payment_transaction_id=p.id),p.updated_at),
                       COALESCE((SELECT max(c.resolved_at) FROM payment_review_cases c
                                  WHERE c.payment_transaction_id=p.id),p.updated_at),
                       COALESCE((SELECT max(j.occurred_at) FROM financial_journals j
                                  WHERE j.payment_transaction_id=p.id),p.updated_at))<:cutoff
                 ORDER BY p.updated_at,p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
                """).param("now", timestamp(now)).param("cutoff", timestamp(cutoff))
                .param("limit", limit).query(String.class).list();
        for (String paymentId : ids) deleteFinancialPayment(paymentId);
        return ids.size();
    }

    private void deleteFinancialPayment(String paymentId) {
        jdbc.sql("""
                DELETE FROM financial_ledger_entries e USING financial_journals j
                 WHERE e.journal_id=j.id AND j.payment_transaction_id=:id
                """).param("id", paymentId).update();
        jdbc.sql("DELETE FROM financial_journals WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM payment_status_history WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM payment_review_cases WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM webhook_logs WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM provider_operations WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM refund_transactions WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM payment_disputes WHERE payment_transaction_id=:id")
                .param("id", paymentId).update();
        jdbc.sql("DELETE FROM payment_transactions WHERE id=:id")
                .param("id", paymentId).update();
    }

    private Watermark outboxWatermark(String slotName) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT confirmed_flush_lsn::text AS lsn,active
                  FROM pg_replication_slots WHERE slot_name=:slot_name
                """).param("slot_name", slotName).query().listOfRows();
        if (rows.size() != 1 || !Boolean.TRUE.equals(rows.getFirst().get("active"))
                || rows.getFirst().get("lsn") == null) return new Watermark(false, null);
        return new Watermark(true, (String) rows.getFirst().get("lsn"));
    }

    private int deleteOutbox(Instant cutoff, String lsn, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT event_id FROM outbox_events
                     WHERE created_at<:cutoff AND commit_lsn<=CAST(:lsn AS pg_lsn)
                     ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                DELETE FROM outbox_events o USING candidates c WHERE o.event_id=c.event_id
                """).param("cutoff", timestamp(cutoff)).param("lsn", lsn)
                .param("limit", limit).update();
    }

    private record Watermark(boolean available, String lsn) {}

    private static Instant monthsBefore(Instant value, int months) {
        return value.atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
    }

    private static Instant yearsBefore(Instant value, int years) {
        return value.atOffset(ZoneOffset.UTC).minusYears(years).toInstant();
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
}

package com.platform.paymentservice.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class JdbcWebhookProcessingAdapter implements WebhookProcessingPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcWebhookProcessingAdapter(JdbcClient jdbc, TransactionTemplate transactions,
                                        ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public ClaimResult claim(String stripeEventId, String owner, Instant now, Duration leaseTtl) {
        return transactions.execute(ignored -> claimOne(stripeEventId, owner, now, leaseTtl));
    }

    @Override
    public List<Claimed> claimDue(String owner, Instant now, Duration leaseTtl, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid webhook claim limit");
        return transactions.execute(ignored -> jdbc.sql("""
                WITH candidates AS (
                    SELECT id
                      FROM webhook_logs
                     WHERE raw_body_ciphertext IS NOT NULL
                       AND ((status IN ('RECEIVED','RETRY_WAIT') AND
                             (next_retry_at IS NULL OR next_retry_at <= :now))
                         OR (status='PROCESSING' AND lease_expires_at <= :now))
                     ORDER BY COALESCE(next_retry_at, received_at), id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE webhook_logs w
                   SET status='PROCESSING', processing_attempts=processing_attempts+1,
                       lease_owner=:owner, lease_token=lease_token+1,
                       lease_expires_at=:lease_expires_at, processing_error=NULL
                  FROM candidates c
                 WHERE w.id=c.id
                RETURNING w.id,w.stripe_event_id,w.event_type,w.event_created_at,
                          w.raw_body_ciphertext,w.raw_body_key_id,w.raw_body_sha256,
                          w.processing_attempts,w.lease_token,w.replay_actor,
                          w.replay_approver,w.replay_cause_ref
                """).param("now", timestamp(now)).param("limit", limit).param("owner", owner)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .query((rs, row) -> claimed(rs, owner)).list());
    }

    @Override
    public boolean completeIgnored(Claimed claim, String reason, Instant completedAt,
                                   String providerObjectId, String paymentId,
                                   String refundId, String disputeId) {
        int changed = jdbc.sql("""
                UPDATE webhook_logs
                   SET status='IGNORED', processing_error=:reason, processed_at=:completed_at,
                       lease_owner=NULL, lease_expires_at=NULL,
                       provider_object_id=COALESCE(:provider_object_id,provider_object_id),
                       payment_transaction_id=COALESCE(:payment_id,payment_transaction_id),
                       refund_transaction_id=COALESCE(:refund_id,refund_transaction_id),
                       dispute_transaction_id=COALESCE(:dispute_id,dispute_transaction_id)
                 WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                   AND lease_token=:lease_token
                   AND lease_expires_at > :completed_at
                """).param("reason", bounded(reason)).param("completed_at", timestamp(completedAt))
                .param("provider_object_id", providerObjectId).param("payment_id", paymentId)
                .param("refund_id", refundId).param("dispute_id", disputeId)
                .param("id", claim.inboxId()).param("owner", claim.owner())
                .param("lease_token", claim.leaseToken()).update();
        return changed == 1;
    }

    @Override
    public boolean completeWithReview(Claimed claim, String paymentId, String providerObjectId,
                                      String reasonCode, String evidenceJson,
                                      String reviewCaseId, Instant completedAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET requires_manual_review=TRUE,
                           last_provider_event_created_at=:event_created_at,
                           last_provider_event_id=:event_id,
                           version=version+1,updated_at=:now
                     WHERE id=:payment_id
                    """).param("event_created_at", timestamp(claim.eventCreatedAt()))
                    .param("event_id", claim.stripeEventId()).param("now", timestamp(completedAt))
                    .param("payment_id", paymentId).update();
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,reason_code,case_status,evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:reason_code,'OPEN',CAST(:evidence AS jsonb),
                            'WEBHOOK',:opened_at)
                    ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                    DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                    """).param("id", reviewCaseId).param("payment_id", paymentId)
                    .param("reason_code", reasonCode).param("evidence", evidenceJson)
                    .param("opened_at", timestamp(completedAt)).update();
            int inbox = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status='PROCESSED',processing_error=:reason_code,
                           processed_at=:now,lease_owner=NULL,lease_expires_at=NULL,
                           provider_object_id=COALESCE(:provider_object_id,provider_object_id),
                           payment_transaction_id=:payment_id
                     WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                       AND lease_token=:lease_token
                   AND lease_expires_at > :now
                """).param("reason_code", reasonCode).param("now", timestamp(completedAt))
                    .param("provider_object_id", providerObjectId).param("payment_id", paymentId)
                    .param("id", claim.inboxId()).param("owner", claim.owner())
                    .param("lease_token", claim.leaseToken()).update();
            if (payment != 1 || review != 1 || inbox != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    @Override
    public boolean completeFinancialObjectWithReview(
            Claimed claim, String paymentId, String refundId, String disputeId,
            Integer expectedAggregateVersion, String providerObjectId,
            String reasonCode, String evidenceJson, String reviewCaseId,
            Instant completedAt) {
        if (refundId != null && disputeId != null)
            throw new IllegalArgumentException("review can target only one financial object");
        if ((refundId != null || disputeId != null) != (expectedAggregateVersion != null))
            throw new IllegalArgumentException("aggregate review fence must be complete");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET requires_manual_review=TRUE,version=version+1,updated_at=:now
                     WHERE id=:payment_id
                    """).param("now", timestamp(completedAt)).param("payment_id", paymentId).update();
            int aggregate = 1;
            if (refundId != null) {
                aggregate = advanceAggregateCheckpoint("refund_transactions", refundId,
                        expectedAggregateVersion, claim, completedAt);
            } else if (disputeId != null) {
                aggregate = advanceAggregateCheckpoint("payment_disputes", disputeId,
                        expectedAggregateVersion, claim, completedAt);
            }
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,refund_transaction_id,reason_code,
                       case_status,evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:refund_id,:reason_code,'OPEN',
                            CAST(:evidence AS jsonb),'WEBHOOK',:opened_at)
                    ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                    DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                    """).param("id", reviewCaseId).param("payment_id", paymentId)
                    .param("refund_id", refundId).param("reason_code", reasonCode)
                    .param("evidence", evidenceJson).param("opened_at", timestamp(completedAt)).update();
            int inbox = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status='PROCESSED',processing_error=:reason_code,processed_at=:now,
                           lease_owner=NULL,lease_expires_at=NULL,
                           provider_object_id=COALESCE(:provider_object_id,provider_object_id),
                           payment_transaction_id=:payment_id,
                           refund_transaction_id=COALESCE(:refund_id,refund_transaction_id),
                           dispute_transaction_id=COALESCE(:dispute_id,dispute_transaction_id)
                     WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                       AND lease_token=:lease_token
                   AND lease_expires_at > :now
                """).param("reason_code", reasonCode).param("now", timestamp(completedAt))
                    .param("provider_object_id", providerObjectId).param("payment_id", paymentId)
                    .param("refund_id", refundId).param("dispute_id", disputeId)
                    .param("id", claim.inboxId()).param("owner", claim.owner())
                    .param("lease_token", claim.leaseToken()).update();
            if (payment != 1 || aggregate != 1 || review != 1 || inbox != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private int advanceAggregateCheckpoint(String table, String aggregateId, int expectedVersion,
                                           Claimed claim, Instant completedAt) {
        if (!"refund_transactions".equals(table) && !"payment_disputes".equals(table))
            throw new IllegalArgumentException("unsupported checkpoint table");
        return jdbc.sql("UPDATE " + table
                        + " SET last_provider_event_created_at=:event_created_at,"
                        + " last_provider_event_id=:event_id,version=version+1,updated_at=:now"
                        + " WHERE id=:id AND version=:expected_version")
                .param("event_created_at", timestamp(claim.eventCreatedAt()))
                .param("event_id", claim.stripeEventId()).param("now", timestamp(completedAt))
                .param("id", aggregateId).param("expected_version", expectedVersion).update();
    }

    @Override
    public boolean completeIgnoredAndAdvancePaymentCheckpoint(
            Claimed claim, String paymentId, int expectedPaymentVersion,
            String reason, String providerObjectId, Instant completedAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET last_provider_event_created_at=:event_created_at,
                           last_provider_event_id=:event_id,version=version+1,updated_at=:now
                     WHERE id=:payment_id AND version=:expected_version
                    """).param("event_created_at", timestamp(claim.eventCreatedAt()))
                    .param("event_id", claim.stripeEventId()).param("now", timestamp(completedAt))
                    .param("payment_id", paymentId).param("expected_version", expectedPaymentVersion)
                    .update();
            int inbox = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status='IGNORED',processing_error=:reason,processed_at=:now,
                           lease_owner=NULL,lease_expires_at=NULL,
                           provider_object_id=COALESCE(:provider_object_id,provider_object_id),
                           payment_transaction_id=:payment_id
                     WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                       AND lease_token=:lease_token
                   AND lease_expires_at > :now
                """).param("reason", bounded(reason)).param("now", timestamp(completedAt))
                    .param("provider_object_id", providerObjectId).param("payment_id", paymentId)
                    .param("id", claim.inboxId()).param("owner", claim.owner())
                    .param("lease_token", claim.leaseToken()).update();
            if (payment != 1 || inbox != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    @Override
    public boolean completeIgnoredAndAdvanceRefundCheckpoint(
            Claimed claim, String refundId, int expectedRefundVersion, String paymentId,
            String reason, String providerObjectId, Instant completedAt) {
        return completeIgnoredAndAdvanceCheckpoint(claim, "refund_transactions", refundId,
                expectedRefundVersion, paymentId, reason, providerObjectId, completedAt, true);
    }

    @Override
    public boolean completeIgnoredAndAdvanceDisputeCheckpoint(
            Claimed claim, String disputeId, int expectedDisputeVersion, String paymentId,
            String reason, String providerObjectId, Instant completedAt) {
        return completeIgnoredAndAdvanceCheckpoint(claim, "payment_disputes", disputeId,
                expectedDisputeVersion, paymentId, reason, providerObjectId, completedAt, false);
    }

    private boolean completeIgnoredAndAdvanceCheckpoint(
            Claimed claim, String table, String aggregateId, int expectedVersion,
            String paymentId, String reason, String providerObjectId,
            Instant completedAt, boolean refund) {
        if (!"refund_transactions".equals(table) && !"payment_disputes".equals(table))
            throw new IllegalArgumentException("unsupported checkpoint table");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            String update = "UPDATE " + table + " SET last_provider_event_created_at=:event_created_at," +
                    " last_provider_event_id=:event_id,version=version+1,updated_at=:now" +
                    " WHERE id=:aggregate_id AND version=:expected_version";
            int aggregate = jdbc.sql(update).param("event_created_at", timestamp(claim.eventCreatedAt()))
                    .param("event_id", claim.stripeEventId()).param("now", timestamp(completedAt))
                    .param("aggregate_id", aggregateId).param("expected_version", expectedVersion)
                    .update();
            int inbox = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status='IGNORED',processing_error=:reason,processed_at=:now,
                           lease_owner=NULL,lease_expires_at=NULL,
                           provider_object_id=COALESCE(:provider_object_id,provider_object_id),
                           payment_transaction_id=:payment_id,
                           refund_transaction_id=CASE WHEN :is_refund THEN :aggregate_id ELSE refund_transaction_id END,
                           dispute_transaction_id=CASE WHEN :is_refund THEN dispute_transaction_id ELSE :aggregate_id END
                     WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                       AND lease_token=:lease_token
                   AND lease_expires_at > :now
                """).param("reason", bounded(reason)).param("now", timestamp(completedAt))
                    .param("provider_object_id", providerObjectId).param("payment_id", paymentId)
                    .param("is_refund", refund).param("aggregate_id", aggregateId)
                    .param("id", claim.inboxId()).param("owner", claim.owner())
                    .param("lease_token", claim.leaseToken()).update();
            if (aggregate != 1 || inbox != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    @Override
    public FailureDisposition recordFailure(Claimed claim, String error, Instant failedAt,
                                            Instant nextRetryAt, int maximumAttempts,
                                            String reviewCaseId) {
        if (maximumAttempts < 1) throw new IllegalArgumentException("maximum attempts must be positive");
        return transactions.execute(status -> {
            boolean exhausted = claim.processingAttempts() >= maximumAttempts;
            int changed = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status=:status, processing_error=:error,
                           next_retry_at=:next_retry_at,
                           processed_at=CASE WHEN :dead THEN CAST(:failed_at AS timestamptz) ELSE NULL END,
                           lease_owner=NULL, lease_expires_at=NULL
                     WHERE id=:id AND status='PROCESSING' AND lease_owner=:owner
                       AND lease_token=:lease_token
                                       AND lease_expires_at > :failed_at
                    """).param("status", exhausted ? "DEAD" : "RETRY_WAIT")
                    .param("error", bounded(error))
                    .param("next_retry_at", exhausted ? null : timestamp(nextRetryAt))
                    .param("dead", exhausted).param("failed_at", timestamp(failedAt))
                    .param("id", claim.inboxId()).param("owner", claim.owner())
                    .param("lease_token", claim.leaseToken()).update();
            if (changed != 1) return FailureDisposition.FENCED;

            if (exhausted) openExhaustedReviewIfMapped(claim, reviewCaseId, failedAt, error);
            return exhausted ? FailureDisposition.DEAD : FailureDisposition.RETRY_SCHEDULED;
        });
    }

    @Override
    public ReplayResult scheduleReplay(String stripeEventId, String actor, String approver,
                                       String causeRef, Instant scheduledAt) {
        return transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT status,raw_body_ciphertext,lease_expires_at
                      FROM webhook_logs WHERE stripe_event_id=:event_id FOR UPDATE
                    """).param("event_id", stripeEventId).query().listOfRows();
            if (rows.isEmpty()) return ReplayResult.MISSING;
            Map<String, Object> row = rows.getFirst();
            if (row.get("raw_body_ciphertext") == null) return ReplayResult.PAYLOAD_SHREDDED;
            Instant leaseExpiresAt = row.get("lease_expires_at") instanceof Timestamp timestamp
                    ? timestamp.toInstant() : null;
            if ("PROCESSING".equals(row.get("status")) && leaseExpiresAt != null
                    && leaseExpiresAt.isAfter(scheduledAt)) return ReplayResult.BUSY;

            int changed = jdbc.sql("""
                    UPDATE webhook_logs
                       SET status='RETRY_WAIT', processing_error=NULL,
                           processing_attempts=0, next_retry_at=:now,
                           lease_owner=NULL, lease_token=lease_token+1,
                           lease_expires_at=NULL, processed_at=NULL,
                           replay_actor=:actor, replay_approver=:approver,
                           replay_cause_ref=:cause_ref
                     WHERE stripe_event_id=:event_id
                    """).param("now", timestamp(scheduledAt)).param("actor", actor)
                    .param("approver", approver).param("cause_ref", causeRef)
                    .param("event_id", stripeEventId).update();
            if (changed != 1) throw new IllegalStateException("webhook replay update was lost");
            return ReplayResult.SCHEDULED;
        });
    }

    private ClaimResult claimOne(String stripeEventId, String owner, Instant now, Duration leaseTtl) {
        Optional<Claimed> claimed = jdbc.sql("""
                UPDATE webhook_logs
                   SET status='PROCESSING', processing_attempts=processing_attempts+1,
                       lease_owner=:owner, lease_token=lease_token+1,
                       lease_expires_at=:lease_expires_at, processing_error=NULL
                 WHERE stripe_event_id=:event_id AND raw_body_ciphertext IS NOT NULL
                   AND ((status IN ('RECEIVED','RETRY_WAIT') AND
                         (next_retry_at IS NULL OR next_retry_at <= :now))
                     OR (status='PROCESSING' AND lease_expires_at <= :now))
                RETURNING id,stripe_event_id,event_type,event_created_at,
                          raw_body_ciphertext,raw_body_key_id,raw_body_sha256,
                          processing_attempts,lease_token,replay_actor,
                          replay_approver,replay_cause_ref
                """).param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("event_id", stripeEventId).param("now", timestamp(now))
                .query((rs, row) -> claimed(rs, owner)).optional();
        if (claimed.isPresent()) return claimed.get();

        Optional<String> status = jdbc.sql("""
                SELECT status FROM webhook_logs WHERE stripe_event_id=:event_id
                """).param("event_id", stripeEventId).query(String.class).optional();
        if (status.isEmpty()) return new Missing();
        return switch (status.get()) {
            case "PROCESSED", "IGNORED", "DEAD" -> new Terminal(status.get());
            default -> new Busy();
        };
    }

    private Claimed claimed(java.sql.ResultSet rs, String owner) throws java.sql.SQLException {
        return new Claimed(rs.getLong("id"), rs.getString("stripe_event_id"),
                rs.getString("event_type"), rs.getTimestamp("event_created_at").toInstant(),
                new EncryptedSecret(rs.getBytes("raw_body_ciphertext"),
                        rs.getString("raw_body_key_id")),
                rs.getString("raw_body_sha256"), rs.getInt("processing_attempts"),
                owner, rs.getLong("lease_token"), rs.getString("replay_actor"),
                rs.getString("replay_approver"), rs.getString("replay_cause_ref"));
    }

    private void openExhaustedReviewIfMapped(Claimed claim, String reviewCaseId,
                                             Instant failedAt, String error) {
        Optional<String> paymentId = jdbc.sql("""
                SELECT payment_transaction_id FROM webhook_logs WHERE id=:id
                """).param("id", claim.inboxId()).query(String.class).optional();
        if (paymentId.isEmpty()) return;
        String evidence = write(List.of(Map.of(
                "stripe_event_id", claim.stripeEventId(),
                "event_type", claim.eventType(),
                "processing_attempts", claim.processingAttempts(),
                "error", bounded(error),
                "observed_at", failedAt.toString())));
        jdbc.sql("""
                INSERT INTO payment_review_cases
                  (id,payment_transaction_id,reason_code,case_status,evidence,opened_by,opened_at)
                VALUES (:id,:payment_id,'WEBHOOK_RETRY_EXHAUSTED','OPEN',
                        CAST(:evidence AS jsonb),'WEBHOOK',:opened_at)
                ON CONFLICT (payment_transaction_id) WHERE case_status IN ('OPEN','IN_PROGRESS')
                DO UPDATE SET evidence=payment_review_cases.evidence || EXCLUDED.evidence
                """).param("id", reviewCaseId).param("payment_id", paymentId.get())
                .param("evidence", evidence).param("opened_at", timestamp(failedAt)).update();
        jdbc.sql("""
                UPDATE payment_transactions SET requires_manual_review=TRUE,
                       version=version+1,updated_at=:now WHERE id=:payment_id
                """).param("now", timestamp(failedAt)).param("payment_id", paymentId.get()).update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("webhook evidence serialization failed", failure);
        }
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

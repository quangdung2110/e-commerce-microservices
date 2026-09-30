package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.ProviderOperationClaim;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public final class JdbcProviderOperationAdapter implements ProviderOperationPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcProviderOperationAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public ProviderOperationClaim createOrClaim(ProviderOperation operation, String owner,
                                                Instant now, Duration leaseTtl) {
        if (operation.status() != ProviderOperationStatus.REQUESTED) {
            throw new IllegalArgumentException("new provider operation must be REQUESTED");
        }
        return transactions.execute(ignored -> createOrClaimInTransaction(operation, owner, now, leaseTtl));
    }

    private ProviderOperationClaim createOrClaimInTransaction(ProviderOperation operation, String owner,
                                                               Instant now, Duration leaseTtl) {
        int inserted = jdbc.sql("""
                INSERT INTO provider_operations
                  (id, payment_transaction_id, refund_transaction_id, operation_type,
                   provider_idempotency_key, command_idempotency_key, request_fingerprint,
                   status, recovery_deadline_at, next_retry_at, lease_owner, lease_token,
                   lease_expires_at, last_heartbeat_at)
                VALUES (:id, :payment_id, :refund_id, :operation_type,
                        :provider_key, :command_key, CAST(:fingerprint AS jsonb),
                        'REQUESTED', :recovery_deadline, :next_retry_at, :owner, 0,
                        :lease_expires_at, :now)
                ON CONFLICT (provider_idempotency_key) DO NOTHING
                """)
                .param("id", operation.id()).param("payment_id", operation.paymentTransactionId())
                .param("refund_id", operation.refundTransactionId()).param("operation_type", operation.type().name())
                .param("provider_key", operation.providerIdempotencyKey())
                .param("command_key", operation.commandIdempotencyKey())
                .param("fingerprint", operation.requestFingerprintJson())
                .param("recovery_deadline", timestamp(operation.recoveryDeadlineAt()))
                .param("next_retry_at", timestamp(operation.nextRetryAt() == null ? now : operation.nextRetryAt()))
                .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).update();
        if (inserted == 1) return new ProviderOperationClaim.Acquired(operation, 0);

        Map<String, Object> row = jdbc.sql("""
                SELECT id, payment_transaction_id, refund_transaction_id, operation_type,
                       provider_idempotency_key, command_idempotency_key,
                       request_fingerprint::text AS request_fingerprint, status,
                       provider_object_id, attempt_count, first_dispatched_at,
                       safe_replay_until, recovery_deadline_at, next_retry_at,
                       lease_owner, lease_token, lease_expires_at,
                       request_fingerprint = CAST(:fingerprint AS jsonb) AS fingerprint_matches
                  FROM provider_operations
                 WHERE provider_idempotency_key = :provider_key
                 FOR UPDATE
                """)
                .param("provider_key", operation.providerIdempotencyKey())
                .param("fingerprint", operation.requestFingerprintJson())
                .query().singleRow();
        ProviderOperation existing = map(row);
        if (!Boolean.TRUE.equals(row.get("fingerprint_matches"))
                || existing.type() != operation.type()
                || !existing.paymentTransactionId().equals(operation.paymentTransactionId())
                || !equalsNullable(existing.refundTransactionId(), operation.refundTransactionId())) {
            return new ProviderOperationClaim.Conflict();
        }
        if (existing.status().isTerminal()) return new ProviderOperationClaim.Terminal(existing);
        Instant nextRetryAt = instant(row.get("next_retry_at"));
        if (nextRetryAt != null && nextRetryAt.isAfter(now)) return new ProviderOperationClaim.NotDue();
        Instant leaseExpiresAt = instant(row.get("lease_expires_at"));
        if (leaseExpiresAt != null && leaseExpiresAt.isAfter(now)) {
            return new ProviderOperationClaim.InProgress();
        }

        long oldToken = ((Number) row.get("lease_token")).longValue();
        int claimed = jdbc.sql("""
                UPDATE provider_operations
                   SET lease_owner = :owner, lease_token = lease_token + 1,
                       lease_expires_at = :lease_expires_at, last_heartbeat_at = :now,
                       updated_at = :now
                 WHERE id = :id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_token = :old_token
                   AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
                """)
                .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("id", existing.id())
                .param("old_token", oldToken).update();
        return claimed == 1
                ? new ProviderOperationClaim.Acquired(existing, oldToken + 1)
                : new ProviderOperationClaim.InProgress();
    }

    @Override
    public boolean markDispatched(String operationId, String owner, long leaseToken,
                                  Instant dispatchedAt, Duration safeReplayHorizon) {
        return transactions.execute(ignored -> jdbc.sql("""
                UPDATE provider_operations
                   SET attempt_count = attempt_count + 1,
                       last_attempted_at = :dispatched_at,
                       first_dispatched_at = COALESCE(first_dispatched_at, :dispatched_at),
                       last_dispatched_at = :dispatched_at,
                       safe_replay_until = COALESCE(safe_replay_until, :safe_replay_until),
                       updated_at = :dispatched_at
                 WHERE id = :id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_owner = :owner AND lease_token = :lease_token
                   AND lease_expires_at > :dispatched_at
                """)
                .param("dispatched_at", timestamp(dispatchedAt))
                .param("safe_replay_until", timestamp(dispatchedAt.plus(safeReplayHorizon)))
                .param("id", operationId).param("owner", owner).param("lease_token", leaseToken)
                .update() == 1);
    }

    @Override
    public boolean finish(String operationId, String owner, long leaseToken,
                          ProviderOperationResult result, Instant finishedAt, Instant nextRetryAt) {
        ProviderOperationStatus status = status(result);
        String objectId = result instanceof ProviderOperationResult.Confirmed confirmed
                ? confirmed.providerObjectId() : null;
        return transactions.execute(ignored -> jdbc.sql("""
                UPDATE provider_operations
                   SET status = :status, provider_object_id = COALESCE(:object_id, provider_object_id),
                       provider_request_id = COALESCE(:request_id, provider_request_id),
                       provider_result = CAST(:provider_result AS jsonb),
                       next_retry_at = :next_retry_at, lease_owner = NULL,
                       lease_expires_at = NULL, last_heartbeat_at = :finished_at,
                       updated_at = :finished_at
                 WHERE id = :id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_owner = :owner AND lease_token = :lease_token
                """)
                .param("status", status.name()).param("object_id", objectId)
                .param("request_id", result.providerRequestId()).param("provider_result", result.resultJson())
                .param("next_retry_at", timestamp(nextRetryAt)).param("finished_at", timestamp(finishedAt))
                .param("id", operationId).param("owner", owner).param("lease_token", leaseToken)
                .update() == 1);
    }

    @Override
    public boolean heartbeat(String operationId, String owner, long leaseToken,
                             Instant now, Duration leaseTtl) {
        return transactions.execute(ignored -> jdbc.sql("""
                UPDATE provider_operations
                   SET lease_expires_at = :lease_expires_at, last_heartbeat_at = :now,
                       updated_at = :now
                 WHERE id = :id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_owner = :owner AND lease_token = :lease_token
                   AND lease_expires_at > :now
                """)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("id", operationId)
                .param("owner", owner).param("lease_token", leaseToken).update() == 1);
    }

    private static ProviderOperation map(Map<String, Object> row) {
        return new ProviderOperation(
                (String) row.get("id"),
                (String) row.get("payment_transaction_id"),
                (String) row.get("refund_transaction_id"),
                ProviderOperationType.valueOf((String) row.get("operation_type")),
                (String) row.get("provider_idempotency_key"),
                (String) row.get("command_idempotency_key"),
                (String) row.get("request_fingerprint"),
                ProviderOperationStatus.valueOf((String) row.get("status")),
                (String) row.get("provider_object_id"),
                ((Number) row.get("attempt_count")).intValue(),
                instant(row.get("first_dispatched_at")),
                instant(row.get("safe_replay_until")),
                instant(row.get("recovery_deadline_at")),
                instant(row.get("next_retry_at")));
    }

    private static ProviderOperationStatus status(ProviderOperationResult result) {
        if (result instanceof ProviderOperationResult.Confirmed) return ProviderOperationStatus.PROVIDER_CONFIRMED;
        if (result instanceof ProviderOperationResult.Rejected) return ProviderOperationStatus.PROVIDER_REJECTED;
        if (result instanceof ProviderOperationResult.Unknown) return ProviderOperationStatus.UNKNOWN;
        return ProviderOperationStatus.MANUAL_REVIEW_REQUIRED;
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}

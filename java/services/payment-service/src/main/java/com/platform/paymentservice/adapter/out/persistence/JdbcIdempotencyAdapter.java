package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.IdempotencyClaim;
import com.platform.paymentservice.application.port.out.IdempotencyPort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public final class JdbcIdempotencyAdapter implements IdempotencyPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcIdempotencyAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public IdempotencyClaim claim(String key, IdempotencyAction action, String fingerprint,
                                  String owner, Instant now, Duration leaseTtl, Instant expiresAt) {
        return transactions.execute(status -> claimInTransaction(
                key, action, fingerprint, owner, now, leaseTtl, expiresAt));
    }

    private IdempotencyClaim claimInTransaction(String key, IdempotencyAction action,
                                                 String fingerprint, String owner, Instant now,
                                                 Duration leaseTtl, Instant expiresAt) {
        int inserted = jdbc.sql("""
                INSERT INTO payment_idempotency
                  (idempotency_key, action, request_fingerprint, status,
                   lease_owner, lease_token, lease_expires_at, last_heartbeat_at, expires_at)
                VALUES (:key, :action, :fingerprint, 'IN_PROGRESS',
                        :owner, 0, :lease_expires_at, :now, :expires_at)
                ON CONFLICT (idempotency_key, action) DO NOTHING
                """)
                .param("key", key).param("action", action.name()).param("fingerprint", fingerprint)
                .param("owner", owner).param("lease_expires_at", Timestamp.from(now.plus(leaseTtl)))
                .param("now", Timestamp.from(now)).param("expires_at", Timestamp.from(expiresAt)).update();
        if (inserted == 1) return new IdempotencyClaim.Acquired(0);

        Map<String, Object> row = jdbc.sql("""
                SELECT request_fingerprint, status, resource_id, response_code,
                       response_body::text AS response_body, lease_token, lease_expires_at
                  FROM payment_idempotency
                 WHERE idempotency_key = :key AND action = :action
                 FOR UPDATE
                """)
                .param("key", key).param("action", action.name())
                .query().singleRow();
        if (!fingerprint.equals(row.get("request_fingerprint"))) return new IdempotencyClaim.KeyReused();
        if ("COMPLETED".equals(row.get("status"))) {
            return new IdempotencyClaim.Completed(new StoredHttpResponse(
                    (String) row.get("resource_id"), ((Number) row.get("response_code")).intValue(),
                    (String) row.get("response_body")));
        }
        Instant leaseExpiresAt = ((Timestamp) row.get("lease_expires_at")).toInstant();
        if (leaseExpiresAt.isAfter(now)) return new IdempotencyClaim.InProgress();
        long oldToken = ((Number) row.get("lease_token")).longValue();
        int taken = jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_owner = :owner, lease_token = lease_token + 1,
                       lease_expires_at = :lease_expires_at, last_heartbeat_at = :now
                 WHERE idempotency_key = :key AND action = :action
                   AND status = 'IN_PROGRESS' AND lease_token = :old_token
                   AND lease_expires_at <= :now
                """)
                .param("owner", owner).param("lease_expires_at", Timestamp.from(now.plus(leaseTtl)))
                .param("now", Timestamp.from(now)).param("key", key).param("action", action.name())
                .param("old_token", oldToken).update();
        return taken == 1 ? new IdempotencyClaim.Acquired(oldToken + 1) : new IdempotencyClaim.InProgress();
    }

    @Override
    public boolean complete(String key, IdempotencyAction action, String owner, long leaseToken,
                            StoredHttpResponse response, Instant completedAt) {
        return transactions.execute(status -> jdbc.sql("""
                UPDATE payment_idempotency
                   SET status = 'COMPLETED', resource_id = :resource_id,
                       response_code = :response_code, response_body = CAST(:response_body AS jsonb),
                       completed_at = :completed_at, lease_owner = NULL,
                       lease_expires_at = NULL, last_heartbeat_at = :completed_at
                 WHERE idempotency_key = :key AND action = :action
                   AND status = 'IN_PROGRESS' AND lease_owner = :owner AND lease_token = :lease_token
                """)
                .param("resource_id", response.resourceId()).param("response_code", response.statusCode())
                .param("response_body", response.bodyJson()).param("completed_at", Timestamp.from(completedAt))
                .param("key", key).param("action", action.name()).param("owner", owner)
                .param("lease_token", leaseToken).update() == 1);
    }

    @Override
    public boolean heartbeat(String key, IdempotencyAction action, String owner, long leaseToken,
                             Instant now, Duration leaseTtl) {
        return transactions.execute(status -> jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_expires_at = :lease_expires_at, last_heartbeat_at = :now
                 WHERE idempotency_key = :key AND action = :action
                   AND status = 'IN_PROGRESS' AND lease_owner = :owner
                   AND lease_token = :lease_token AND lease_expires_at > :now
                """)
                .param("lease_expires_at", Timestamp.from(now.plus(leaseTtl)))
                .param("now", Timestamp.from(now)).param("key", key)
                .param("action", action.name()).param("owner", owner)
                .param("lease_token", leaseToken).update() == 1);
    }
}

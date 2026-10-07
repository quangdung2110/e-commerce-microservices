package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.ProviderRecoveryPort;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public final class JdbcProviderRecoveryAdapter implements ProviderRecoveryPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcProviderRecoveryAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<Claimed> claimDue(String owner, Instant now, Duration leaseTtl, int batchSize) {
        return transactions.execute(ignored -> claimInTransaction(owner, now, leaseTtl, batchSize));
    }

    private List<Claimed> claimInTransaction(String owner, Instant now,
                                             Duration leaseTtl, int batchSize) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT o.id AS operation_id,o.payment_transaction_id,o.refund_transaction_id,
                       o.operation_type,o.provider_idempotency_key,o.command_idempotency_key,
                       o.request_fingerprint::text AS request_fingerprint,o.status AS operation_status,
                       o.provider_object_id,o.attempt_count,o.first_dispatched_at,o.safe_replay_until,
                       o.recovery_deadline_at,o.next_retry_at,o.lease_token AS operation_lease_token,
                       p.order_id,p.payment_attempt_number,p.amount_minor,p.currency,
                       p.status AS payment_status,p.version AS payment_version,
                       p.financial_state_version,p.provider_transaction_id,p.last_error_code,
                       p.expires_at AS payment_expires_at,
                       r.refund_amount_minor,r.currency AS refund_currency
                  FROM provider_operations o
                  JOIN payment_transactions p ON p.id=o.payment_transaction_id
                  LEFT JOIN refund_transactions r ON r.id=o.refund_transaction_id
                 WHERE o.status IN ('REQUESTED','UNKNOWN')
                   AND (o.next_retry_at IS NULL OR o.next_retry_at<=:now)
                   AND (o.lease_expires_at IS NULL OR o.lease_expires_at<=:now)
                 ORDER BY COALESCE(o.next_retry_at,o.created_at),o.created_at
                 LIMIT :limit
                 FOR UPDATE OF o SKIP LOCKED
                """).param("now", timestamp(now)).param("limit", batchSize)
                .query().listOfRows();
        List<Claimed> claimed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ProviderOperationType type = ProviderOperationType.valueOf((String) row.get("operation_type"));
            String commandKey = (String) row.get("command_idempotency_key");
            long commandToken = commandKey == null ? 0 : claimCommand(commandKey, action(type), owner, now, leaseTtl);
            if (commandKey != null && commandToken < 0) continue;
            long oldOperationToken = number(row, "operation_lease_token");
            int operationClaimed = jdbc.sql("""
                    UPDATE provider_operations
                       SET lease_owner=:owner,lease_token=lease_token+1,
                           lease_expires_at=:lease_expires,last_heartbeat_at=:now,updated_at=:now
                     WHERE id=:id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_token=:token
                       AND (lease_expires_at IS NULL OR lease_expires_at<=:now)
                       AND (next_retry_at IS NULL OR next_retry_at<=:now)
                    """).param("owner", owner).param("lease_expires", timestamp(now.plus(leaseTtl)))
                    .param("now", timestamp(now)).param("id", row.get("operation_id"))
                    .param("token", oldOperationToken).update();
            if (operationClaimed != 1) throw new IllegalStateException("locked provider operation was not claimable");
            ProviderOperation operation = map(row);
            long operationToken = oldOperationToken + 1;
            switch (type) {
                case CREATE -> claimed.add(new Create(operation, commandToken, operationToken,
                        number(row, "order_id"), integer(row, "payment_attempt_number"),
                        number(row, "amount_minor"), instant(row.get("payment_expires_at"))));
                case CANCEL -> claimed.add(new Cancel(operation, commandToken, operationToken,
                        number(row, "order_id"), integer(row, "payment_attempt_number"),
                        number(row, "amount_minor"), (String) row.get("currency"),
                        (String) row.get("payment_status"), integer(row, "payment_version"),
                        number(row, "financial_state_version"),
                        (String) row.get("provider_transaction_id"), operationReason(row),
                        commandKey != null, (String) row.get("last_error_code")));
                case REFUND -> claimed.add(new Refund(operation, commandToken, operationToken,
                        number(row, "order_id"), integer(row, "payment_attempt_number"),
                        number(row, "refund_amount_minor"), (String) row.get("refund_currency"),
                        integer(row, "payment_version"), number(row, "financial_state_version")));
            }
        }
        return List.copyOf(claimed);
    }

    private long claimCommand(String key, String action, String owner, Instant now, Duration leaseTtl) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT lease_token,lease_expires_at,status
                  FROM payment_idempotency
                 WHERE idempotency_key=:key AND action=:action
                 FOR UPDATE
                """).param("key", key).param("action", action).query().listOfRows();
        if (rows.isEmpty() || !"IN_PROGRESS".equals(rows.getFirst().get("status"))) return -1;
        Instant leaseExpires = instant(rows.getFirst().get("lease_expires_at"));
        if (leaseExpires != null && leaseExpires.isAfter(now)) return -1;
        long token = number(rows.getFirst(), "lease_token");
        int changed = jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_owner=:owner,lease_token=lease_token+1,
                       lease_expires_at=:lease_expires,last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action=:action AND status='IN_PROGRESS'
                   AND lease_token=:token AND lease_expires_at<=:now
                """).param("owner", owner).param("lease_expires", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("key", key).param("action", action)
                .param("token", token).update();
        return changed == 1 ? token + 1 : -1;
    }

    private static ProviderOperation map(Map<String, Object> row) {
        return new ProviderOperation((String) row.get("operation_id"),
                (String) row.get("payment_transaction_id"), (String) row.get("refund_transaction_id"),
                ProviderOperationType.valueOf((String) row.get("operation_type")),
                (String) row.get("provider_idempotency_key"),
                (String) row.get("command_idempotency_key"), (String) row.get("request_fingerprint"),
                ProviderOperationStatus.valueOf((String) row.get("operation_status")),
                (String) row.get("provider_object_id"), integer(row, "attempt_count"),
                instant(row.get("first_dispatched_at")), instant(row.get("safe_replay_until")),
                instant(row.get("recovery_deadline_at")), instant(row.get("next_retry_at")));
    }

    private static String operationReason(Map<String, Object> row) {
        String json = (String) row.get("request_fingerprint");
        int marker = json == null ? -1 : json.indexOf("\"reason\"");
        if (marker < 0) return "EXPIRATION";
        int colon = json.indexOf(':', marker);
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return firstQuote >= 0 && secondQuote > firstQuote
                ? json.substring(firstQuote + 1, secondQuote) : "EXPIRATION";
    }

    private static String action(ProviderOperationType type) {
        return switch (type) {
            case CREATE -> "INITIATE";
            case CANCEL -> "CANCEL";
            case REFUND -> "REFUND";
        };
    }

    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static int integer(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).intValue();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}

package com.platform.paymentservice.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.InitiatePaymentPort;
import com.platform.paymentservice.application.port.out.InitiatePaymentPreparation;
import com.platform.paymentservice.application.service.InitiatePaymentCommand;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class JdbcInitiatePaymentAdapter implements InitiatePaymentPort {
    private static final String EVENT_TYPE = "com.ecommerce.payment.transaction.initiated.v2";
    private static final String EVENT_SCHEMA =
            "/contracts/events/payment/v2/schemas/payment-initiated.schema.json";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcInitiatePaymentAdapter(JdbcClient jdbc, TransactionTemplate transactions,
                                      ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public InitiatePaymentPreparation prepare(
            InitiatePaymentCommand command,
            String commandFingerprint,
            ProviderOperation operation,
            String owner,
            Instant now,
            Duration leaseTtl,
            Instant paymentExpiresAt,
            Instant idempotencyExpiresAt) {
        return transactions.execute(status -> prepareInTransaction(
                command, commandFingerprint, operation, owner, now, leaseTtl,
                paymentExpiresAt, idempotencyExpiresAt, status));
    }

    private InitiatePaymentPreparation prepareInTransaction(
            InitiatePaymentCommand command,
            String commandFingerprint,
            ProviderOperation operation,
            String owner,
            Instant now,
            Duration leaseTtl,
            Instant paymentExpiresAt,
            Instant idempotencyExpiresAt,
            TransactionStatus transactionStatus) {
        int insertedCommand = jdbc.sql("""
                INSERT INTO payment_idempotency
                  (idempotency_key, action, request_fingerprint, status,
                   lease_owner, lease_token, lease_expires_at, last_heartbeat_at, expires_at)
                VALUES (:key, 'INITIATE', :fingerprint, 'IN_PROGRESS',
                        :owner, 0, :lease_expires_at, :now, :expires_at)
                ON CONFLICT (idempotency_key, action) DO NOTHING
                """)
                .param("key", command.idempotencyKey())
                .param("fingerprint", commandFingerprint)
                .param("owner", owner)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now))
                .param("expires_at", timestamp(idempotencyExpiresAt))
                .update();
        if (insertedCommand == 0) {
            return prepareExisting(command, commandFingerprint, owner, now, leaseTtl);
        }

        InitiatePaymentPreparation invalid = validateAttemptSequence(command);
        if (invalid != null) {
            transactionStatus.setRollbackOnly();
            return invalid;
        }

        int insertedPayment = jdbc.sql("""
                INSERT INTO payment_transactions
                  (id, order_id, payment_attempt_number, user_id, amount_minor,
                   currency, status, expires_at, financial_state_version, version)
                VALUES (:id, :order_id, :attempt, :user_id, :amount_minor,
                        'VND', 'INITIATING', :expires_at, 0, 0)
                ON CONFLICT (order_id, payment_attempt_number) DO NOTHING
                """)
                .param("id", operation.paymentTransactionId())
                .param("order_id", command.orderId())
                .param("attempt", command.paymentAttemptNumber())
                .param("user_id", command.userId())
                .param("amount_minor", command.amount().amountMinor())
                .param("expires_at", timestamp(paymentExpiresAt))
                .update();
        if (insertedPayment != 1) {
            transactionStatus.setRollbackOnly();
            return new InitiatePaymentPreparation.AttemptAlreadyExists();
        }

        jdbc.sql("""
                INSERT INTO provider_operations
                  (id, payment_transaction_id, operation_type, provider_idempotency_key,
                   command_idempotency_key, request_fingerprint, status,
                   recovery_deadline_at, next_retry_at, lease_owner, lease_token,
                   lease_expires_at, last_heartbeat_at)
                VALUES (:id, :payment_id, 'CREATE', :provider_key,
                        :command_key, CAST(:request_fingerprint AS jsonb), 'REQUESTED',
                        :recovery_deadline, :next_retry_at, :owner, 0,
                        :lease_expires_at, :now)
                """)
                .param("id", operation.id())
                .param("payment_id", operation.paymentTransactionId())
                .param("provider_key", operation.providerIdempotencyKey())
                .param("command_key", operation.commandIdempotencyKey())
                .param("request_fingerprint", operation.requestFingerprintJson())
                .param("recovery_deadline", timestamp(operation.recoveryDeadlineAt()))
                .param("next_retry_at", timestamp(operation.nextRetryAt()))
                .param("owner", owner)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now))
                .update();
        return new InitiatePaymentPreparation.Acquired(operation, 0, 0, paymentExpiresAt);
    }

    private InitiatePaymentPreparation prepareExisting(
            InitiatePaymentCommand command,
            String commandFingerprint,
            String owner,
            Instant now,
            Duration leaseTtl) {
        Map<String, Object> commandRow = jdbc.sql("""
                SELECT request_fingerprint, status, resource_id, response_code,
                       lease_token, lease_expires_at
                  FROM payment_idempotency
                 WHERE idempotency_key = :key AND action = 'INITIATE'
                 FOR UPDATE
                """)
                .param("key", command.idempotencyKey()).query().singleRow();
        if (!commandFingerprint.equals(commandRow.get("request_fingerprint"))) {
            return new InitiatePaymentPreparation.KeyReused();
        }
        if ("COMPLETED".equals(commandRow.get("status"))) {
            int responseCode = ((Number) commandRow.get("response_code")).intValue();
            if (responseCode != 200) {
                return new InitiatePaymentPreparation.ProviderRejected(
                        (String) commandRow.get("resource_id"));
            }
            return replay((String) commandRow.get("resource_id"));
        }
        Instant commandLeaseExpiry = instant(commandRow.get("lease_expires_at"));
        if (commandLeaseExpiry != null && commandLeaseExpiry.isAfter(now)) {
            return new InitiatePaymentPreparation.InProgress();
        }

        Map<String, Object> operationRow = jdbc.sql("""
                SELECT o.id, o.payment_transaction_id, o.refund_transaction_id,
                       o.operation_type, o.provider_idempotency_key,
                       o.command_idempotency_key, o.request_fingerprint::text AS request_fingerprint,
                       o.status, o.provider_object_id, o.attempt_count,
                       o.first_dispatched_at, o.safe_replay_until,
                       o.recovery_deadline_at, o.next_retry_at,
                       o.lease_token, o.lease_expires_at,
                       p.payment_attempt_number, p.expires_at AS payment_expires_at
                  FROM provider_operations o
                  JOIN payment_transactions p ON p.id = o.payment_transaction_id
                 WHERE o.operation_type = 'CREATE' AND o.command_idempotency_key = :command_key
                 FOR UPDATE OF o
                """)
                .param("command_key", command.idempotencyKey())
                .query().singleRow();
        ProviderOperation operation = mapOperation(operationRow);
        if (operation.status().isTerminal()) {
            return new InitiatePaymentPreparation.Recovering(
                    operation.paymentTransactionId(),
                    ((Number) operationRow.get("payment_attempt_number")).intValue());
        }
        Instant operationLeaseExpiry = instant(operationRow.get("lease_expires_at"));
        Instant nextRetryAt = operation.nextRetryAt();
        if ((operationLeaseExpiry != null && operationLeaseExpiry.isAfter(now))
                || (nextRetryAt != null && nextRetryAt.isAfter(now))) {
            return new InitiatePaymentPreparation.Recovering(
                    operation.paymentTransactionId(),
                    ((Number) operationRow.get("payment_attempt_number")).intValue());
        }

        long oldCommandToken = ((Number) commandRow.get("lease_token")).longValue();
        long oldOperationToken = ((Number) operationRow.get("lease_token")).longValue();
        int commandClaimed = jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_owner = :owner, lease_token = lease_token + 1,
                       lease_expires_at = :lease_expires_at, last_heartbeat_at = :now
                 WHERE idempotency_key = :key AND action = 'INITIATE'
                   AND status = 'IN_PROGRESS' AND lease_token = :old_token
                   AND lease_expires_at <= :now
                """)
                .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("key", command.idempotencyKey())
                .param("old_token", oldCommandToken).update();
        int operationClaimed = jdbc.sql("""
                UPDATE provider_operations
                   SET lease_owner = :owner, lease_token = lease_token + 1,
                       lease_expires_at = :lease_expires_at, last_heartbeat_at = :now,
                       updated_at = :now
                 WHERE id = :id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_token = :old_token
                   AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
                """)
                .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("id", operation.id())
                .param("old_token", oldOperationToken).update();
        if (commandClaimed != 1 || operationClaimed != 1) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return new InitiatePaymentPreparation.InProgress();
        }
        return new InitiatePaymentPreparation.Acquired(
                operation, oldCommandToken + 1, oldOperationToken + 1,
                instant(operationRow.get("payment_expires_at")));
    }

    private InitiatePaymentPreparation validateAttemptSequence(InitiatePaymentCommand command) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT payment_attempt_number, status
                  FROM payment_transactions
                 WHERE order_id = :order_id
                 ORDER BY payment_attempt_number DESC
                 FOR UPDATE
                """)
                .param("order_id", command.orderId()).query().listOfRows();
        if (rows.isEmpty()) {
            return command.paymentAttemptNumber() == 1
                    ? null : new InitiatePaymentPreparation.InvalidAttemptSequence();
        }
        int latestAttempt = ((Number) rows.getFirst().get("payment_attempt_number")).intValue();
        if (command.paymentAttemptNumber() <= latestAttempt) {
            return new InitiatePaymentPreparation.AttemptAlreadyExists();
        }
        String latestStatus = (String) rows.getFirst().get("status");
        if (command.paymentAttemptNumber() != latestAttempt + 1
                || !(latestStatus.equals("FAILED")
                || latestStatus.equals("EXPIRED")
                || latestStatus.equals("CANCELED"))) {
            return new InitiatePaymentPreparation.InvalidAttemptSequence();
        }
        return null;
    }

    @Override
    public boolean completeConfirmed(ConfirmedInitiation completion) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET status = 'PENDING', provider_transaction_id = :provider_id,
                           provider_status_snapshot = 'requires_payment_method',
                           provider_synced_at = :completed_at,
                           client_secret_ciphertext = :ciphertext,
                           client_secret_key_id = :key_id,
                           client_expires_at = :client_expires_at,
                           financial_state_version = 1, version = version + 1,
                           updated_at = :completed_at
                     WHERE id = :payment_id AND status = 'INITIATING' AND version = 0
                    """)
                    .param("provider_id", completion.providerTransactionId())
                    .param("completed_at", timestamp(completion.completedAt()))
                    .param("ciphertext", completion.encryptedClientSecret().ciphertext())
                    .param("key_id", completion.encryptedClientSecret().keyId())
                    .param("client_expires_at", timestamp(completion.clientExpiresAt()))
                    .param("payment_id", completion.transactionId()).update();
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status = 'PROVIDER_CONFIRMED', provider_object_id = :provider_id,
                           provider_result = CAST(:provider_result AS jsonb),
                           provider_request_id = :provider_request_id,
                           next_retry_at = NULL, lease_owner = NULL, lease_expires_at = NULL,
                           updated_at = :completed_at
                     WHERE id = :operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner = :owner AND lease_token = :lease_token
                   AND lease_expires_at > :completed_at
                """)
                    .param("provider_id", completion.providerTransactionId())
                    .param("provider_result", completion.sanitizedProviderResultJson())
                    .param("provider_request_id", completion.providerRequestId())
                    .param("completed_at", timestamp(completion.completedAt()))
                    .param("operation_id", completion.operationId())
                    .param("owner", completion.owner())
                    .param("lease_token", completion.operationLeaseToken()).update();
            String responseBody = write(Map.of(
                    "transaction_id", completion.transactionId(),
                    "payment_attempt_number", completion.paymentAttemptNumber(),
                    "status", "PENDING"));
            int command = jdbc.sql("""
                    UPDATE payment_idempotency
                       SET status = 'COMPLETED', resource_id = :payment_id,
                           response_code = 200, response_body = CAST(:response_body AS jsonb),
                           completed_at = :completed_at, lease_owner = NULL,
                           lease_expires_at = NULL, last_heartbeat_at = :completed_at
                     WHERE idempotency_key = :key AND action = 'INITIATE'
                       AND status = 'IN_PROGRESS' AND lease_owner = :owner
                       AND lease_token = :lease_token
                   AND lease_expires_at > :completed_at
                """)
                    .param("payment_id", completion.transactionId())
                    .param("response_body", responseBody)
                    .param("completed_at", timestamp(completion.completedAt()))
                    .param("key", completion.idempotencyKey())
                    .param("owner", completion.owner())
                    .param("lease_token", completion.commandLeaseToken()).update();
            if (payment != 1 || operation != 1 || command != 1) {
                status.setRollbackOnly();
                return false;
            }

            jdbc.sql("""
                    INSERT INTO payment_status_history
                      (payment_transaction_id, transaction_type, transition_id,
                       old_status, new_status, financial_state_version, triggered_by,
                       cause_ref, actor, provider_response, created_at)
                    VALUES (:payment_id, 'PAYMENT', 'T0.1', 'INITIATING', 'PENDING', 1,
                            'API', :cause_ref, 'order-service',
                            CAST(:provider_response AS jsonb), :created_at)
                    """)
                    .param("payment_id", completion.transactionId())
                    .param("cause_ref", completion.idempotencyKey())
                    .param("provider_response", completion.sanitizedProviderResultJson())
                    .param("created_at", timestamp(completion.completedAt())).update();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schema_version", 2);
            data.put("order_id", Long.toString(completion.orderId()));
            data.put("payment_transaction_id", completion.transactionId());
            data.put("payment_attempt_number", completion.paymentAttemptNumber());
            data.put("financial_state_version", 1);
            data.put("occurred_at", completion.completedAt().toString());
            data.put("amount", Map.of("amount_minor", completion.amountMinor(), "currency", "VND"));
            data.put("expires_at", completion.paymentExpiresAt().toString());

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("specversion", "1.0");
            event.put("id", completion.eventId().toString());
            event.put("source", "/services/payment");
            event.put("subject", "payments/" + completion.transactionId());
            event.put("type", EVENT_TYPE);
            event.put("time", completion.completedAt().toString());
            event.put("datacontenttype", "application/json");
            event.put("dataschema", EVENT_SCHEMA);
            event.put("correlationid", completion.correlationId());
            event.put("data", data);
            Map<String, Object> headers = Map.of(
                    "event_id", completion.eventId().toString(),
                    "event_type", EVENT_TYPE,
                    "correlationid", completion.correlationId());
            jdbc.sql("""
                    INSERT INTO outbox_events
                      (event_id, aggregate_id, event_type, partition_key,
                       headers, payload, created_at)
                    VALUES (:event_id, :aggregate_id, :event_type, :partition_key,
                            CAST(:headers AS jsonb), CAST(:payload AS jsonb), :created_at)
                    """)
                    .param("event_id", completion.eventId())
                    .param("aggregate_id", completion.transactionId())
                    .param("event_type", EVENT_TYPE)
                    .param("partition_key", Long.toString(completion.orderId()))
                    .param("headers", write(headers)).param("payload", write(event))
                    .param("created_at", timestamp(completion.completedAt())).update();
            return true;
        }));
    }

    @Override
    public boolean recordUnknown(UnknownInitiation unknown) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status = 'UNKNOWN', provider_result = CAST(:provider_result AS jsonb),
                           provider_request_id = :provider_request_id,
                           next_retry_at = :next_retry_at, lease_owner = NULL,
                           lease_expires_at = NULL, updated_at = :recorded_at
                     WHERE id = :operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner = :owner AND lease_token = :lease_token
                   AND lease_expires_at > :recorded_at
                """)
                    .param("provider_result", unknown.sanitizedProviderResultJson())
                    .param("provider_request_id", unknown.providerRequestId())
                    .param("next_retry_at", timestamp(unknown.nextRetryAt()))
                    .param("recorded_at", timestamp(unknown.recordedAt()))
                    .param("operation_id", unknown.operationId())
                    .param("owner", unknown.owner())
                    .param("lease_token", unknown.operationLeaseToken()).update();
            int command = expireCommandLease(
                    unknown.idempotencyKey(), unknown.owner(), unknown.commandLeaseToken(), unknown.recordedAt());
            if (operation != 1 || command != 1) status.setRollbackOnly();
            return operation == 1 && command == 1;
        }));
    }

    @Override
    public boolean recordTerminalFailure(TerminalInitiationFailure failure) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            String providerStatus = failure.reviewRequired()
                    ? "MANUAL_REVIEW_REQUIRED" : "PROVIDER_REJECTED";
            String reasonCode = failure.reviewRequired()
                    ? "PROVIDER_IDENTITY_UNBOUND" : "PROVIDER_CREATE_REJECTED";
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status = :provider_status,
                           provider_result = CAST(:provider_result AS jsonb),
                           provider_request_id = :provider_request_id,
                           next_retry_at = NULL, lease_owner = NULL,
                           lease_expires_at = NULL, updated_at = :recorded_at
                     WHERE id = :operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner = :owner AND lease_token = :lease_token
                   AND lease_expires_at > :recorded_at
                """)
                    .param("provider_status", providerStatus)
                    .param("provider_result", failure.sanitizedProviderResultJson())
                    .param("provider_request_id", failure.providerRequestId())
                    .param("recorded_at", timestamp(failure.recordedAt()))
                    .param("operation_id", failure.operationId())
                    .param("owner", failure.owner())
                    .param("lease_token", failure.operationLeaseToken()).update();
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET requires_manual_review = TRUE, version = version + 1,
                           updated_at = :recorded_at
                     WHERE id = :payment_id AND status = 'INITIATING'
                    """)
                    .param("recorded_at", timestamp(failure.recordedAt()))
                    .param("payment_id", failure.transactionId()).update();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("db_status", "INITIATING");
            evidence.put("provider_status", providerStatus);
            evidence.put("operation_id", failure.operationId());
            evidence.put("observed_at", failure.recordedAt().toString());
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id, payment_transaction_id, reason_code, case_status,
                       evidence, opened_by, opened_at)
                    VALUES (:id, :payment_id, :reason_code, 'OPEN',
                            CAST(:evidence AS jsonb), 'API', :opened_at)
                    """)
                    .param("id", failure.reviewCaseId())
                    .param("payment_id", failure.transactionId())
                    .param("reason_code", reasonCode)
                    .param("evidence", write(List.of(evidence)))
                    .param("opened_at", timestamp(failure.recordedAt())).update();
            int command;
            if (failure.reviewRequired()) {
                command = expireCommandLease(
                        failure.idempotencyKey(), failure.owner(), failure.commandLeaseToken(),
                        failure.recordedAt());
            } else {
                String responseBody = write(Map.of(
                        "code", "PROVIDER_REQUEST_REJECTED",
                        "retryable", false,
                        "correlation_id", failure.correlationId()));
                command = jdbc.sql("""
                        UPDATE payment_idempotency
                           SET status = 'COMPLETED', resource_id = :payment_id,
                               response_code = 503,
                               response_body = CAST(:response_body AS jsonb),
                               completed_at = :recorded_at, lease_owner = NULL,
                               lease_expires_at = NULL, last_heartbeat_at = :recorded_at
                         WHERE idempotency_key = :key AND action = 'INITIATE'
                           AND status = 'IN_PROGRESS' AND lease_owner = :owner
                           AND lease_token = :lease_token
                   AND lease_expires_at > :recorded_at
                """)
                        .param("payment_id", failure.transactionId())
                        .param("response_body", responseBody)
                        .param("recorded_at", timestamp(failure.recordedAt()))
                        .param("key", failure.idempotencyKey())
                        .param("owner", failure.owner())
                        .param("lease_token", failure.commandLeaseToken()).update();
            }
            if (operation != 1 || payment != 1 || review != 1 || command != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private int expireCommandLease(String key, String owner, long token, Instant now) {
        return jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_expires_at = :now, last_heartbeat_at = :now
                 WHERE idempotency_key = :key AND action = 'INITIATE'
                   AND status = 'IN_PROGRESS' AND lease_owner = :owner
                   AND lease_token = :lease_token
                   AND lease_expires_at > :now
                """)
                .param("now", timestamp(now)).param("key", key)
                .param("owner", owner).param("lease_token", token).update();
    }

    private InitiatePaymentPreparation.Replay replay(String transactionId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT id, payment_attempt_number, status, client_secret_ciphertext,
                       client_secret_key_id, client_expires_at, expires_at
                  FROM payment_transactions WHERE id = :id
                """)
                .param("id", transactionId).query().singleRow();
        byte[] ciphertext = (byte[]) row.get("client_secret_ciphertext");
        String keyId = (String) row.get("client_secret_key_id");
        EncryptedSecret encrypted = ciphertext == null ? null : new EncryptedSecret(ciphertext, keyId);
        return new InitiatePaymentPreparation.Replay(
                (String) row.get("id"),
                ((Number) row.get("payment_attempt_number")).intValue(),
                PaymentStatus.valueOf((String) row.get("status")), encrypted,
                instant(row.get("client_expires_at")), instant(row.get("expires_at")));
    }

    private static ProviderOperation mapOperation(Map<String, Object> row) {
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

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize initiate payment data", impossible);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}

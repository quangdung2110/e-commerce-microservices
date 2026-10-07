package com.platform.paymentservice.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.RefundPaymentPort;
import com.platform.paymentservice.application.port.out.RefundPaymentPreparation;
import com.platform.paymentservice.application.service.RefundPaymentCommand;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class JdbcRefundPaymentAdapter implements RefundPaymentPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcRefundPaymentAdapter(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public RefundPaymentPreparation prepare(
            RefundPaymentCommand command, String commandFingerprint, ProviderOperation requested,
            String owner, Instant now, Duration leaseTtl, Instant commandExpiresAt,
            UUID initiatedEventId, String evidenceJson) {
        return transactions.execute(status -> {
            CommandClaim commandClaim = claimCommand(command, commandFingerprint, owner, now,
                    leaseTtl, commandExpiresAt);
            if (commandClaim.result != null) return commandClaim.result;

            List<Map<String, Object>> existingRefund = jdbc.sql("""
                    SELECT r.id AS refund_id, r.payment_transaction_id, r.order_id,
                           p.payment_attempt_number, p.amount_minor, p.currency,
                           p.version AS payment_version, p.financial_state_version,
                           o.id AS operation_id, o.provider_idempotency_key,
                           o.request_fingerprint::text AS request_fingerprint, o.status AS operation_status,
                           o.provider_object_id, o.attempt_count, o.first_dispatched_at,
                           o.safe_replay_until, o.recovery_deadline_at, o.next_retry_at,
                           o.lease_token, o.lease_expires_at
                      FROM refund_transactions r
                      JOIN payment_transactions p ON p.id=r.payment_transaction_id
                      JOIN provider_operations o ON o.refund_transaction_id=r.id AND o.operation_type='REFUND'
                     WHERE r.command_idempotency_key=:key FOR UPDATE OF r,p,o
                    """).param("key", command.idempotencyKey()).query().listOfRows();
            if (!existingRefund.isEmpty()) {
                Map<String, Object> row = existingRefund.getFirst();
                RefundPaymentPreparation recovered = claimExisting(
                        row, commandClaim.token, owner, now, leaseTtl);
                if (recovered instanceof RefundPaymentPreparation.InProgress)
                    expireCommandLease(command.idempotencyKey(), owner, commandClaim.token, now);
                return recovered;
            }

            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT id,order_id,payment_attempt_number,amount_minor,currency,
                           provider_transaction_id,status,version,financial_state_version
                      FROM payment_transactions WHERE id=:id FOR UPDATE
                    """).param("id", command.transactionId()).query().listOfRows();
            if (rows.isEmpty()) { status.setRollbackOnly(); return new RefundPaymentPreparation.PaymentNotFound(); }
            Map<String, Object> payment = rows.getFirst();
            if (number(payment, "order_id") != command.orderId()) {
                status.setRollbackOnly(); return new RefundPaymentPreparation.OrderMismatch();
            }
            if (!jdbc.sql("SELECT id FROM refund_transactions WHERE order_id=:order_id")
                    .param("order_id", command.orderId()).query(String.class).list().isEmpty()) {
                status.setRollbackOnly(); return new RefundPaymentPreparation.OrderRefundAlreadyExists();
            }
            if (!"SUCCESS".equals(payment.get("status"))) {
                status.setRollbackOnly(); return new RefundPaymentPreparation.PaymentNotSuccess();
            }
            if (command.amount().amountMinor() > number(payment, "amount_minor")) {
                status.setRollbackOnly(); return new RefundPaymentPreparation.AmountExceeded();
            }

            String providerTransactionId = (String) payment.get("provider_transaction_id");
            String providerFingerprint = requested.requestFingerprintJson()
                    .replace("\"provider_transaction_id\":null",
                            "\"provider_transaction_id\":\"" + providerTransactionId + "\"");
            ProviderOperation operation = new ProviderOperation(requested.id(), requested.paymentTransactionId(),
                    requested.refundTransactionId(), requested.type(), requested.providerIdempotencyKey(),
                    requested.commandIdempotencyKey(), providerFingerprint, requested.status(),
                    requested.providerObjectId(), requested.attemptCount(), requested.firstDispatchedAt(),
                    requested.safeReplayUntil(), requested.recoveryDeadlineAt(), requested.nextRetryAt());
            long financialVersion = number(payment, "financial_state_version") + 1;
            jdbc.sql("""
                    INSERT INTO refund_transactions
                      (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                       refund_amount_minor,currency,reason,inventory_evidence,status)
                    VALUES (:id,:payment_id,:order_id,:request_id,:command_key,
                            :amount,:currency,:reason,CAST(:evidence AS jsonb),'PENDING')
                    """).param("id", operation.refundTransactionId())
                    .param("payment_id", command.transactionId()).param("order_id", command.orderId())
                    .param("request_id", command.refundRequestId()).param("command_key", command.idempotencyKey())
                    .param("amount", command.amount().amountMinor()).param("currency", command.amount().currency())
                    .param("reason", command.reason().name()).param("evidence", evidenceJson).update();
            int paymentChanged = jdbc.sql("""
                    UPDATE payment_transactions
                       SET status='REFUNDING',financial_state_version=:financial_version,
                           version=version+1,updated_at=:now
                     WHERE id=:id AND status='SUCCESS' AND version=:version
                    """).param("financial_version", financialVersion).param("now", timestamp(now))
                    .param("id", command.transactionId()).param("version", integer(payment, "version")).update();
            if (paymentChanged != 1) { status.setRollbackOnly(); return new RefundPaymentPreparation.InProgress(); }
            jdbc.sql("""
                    INSERT INTO provider_operations
                      (id,payment_transaction_id,refund_transaction_id,operation_type,
                       provider_idempotency_key,command_idempotency_key,request_fingerprint,status,
                       recovery_deadline_at,next_retry_at,lease_owner,lease_token,lease_expires_at,last_heartbeat_at)
                    VALUES (:id,:payment_id,:refund_id,'REFUND',:provider_key,:command_key,
                            CAST(:fingerprint AS jsonb),'REQUESTED',:deadline,:now,:owner,0,:lease_expires_at,:now)
                    """).param("id", operation.id()).param("payment_id", command.transactionId())
                    .param("refund_id", operation.refundTransactionId()).param("provider_key", operation.providerIdempotencyKey())
                    .param("command_key", command.idempotencyKey()).param("fingerprint", providerFingerprint)
                    .param("deadline", timestamp(operation.recoveryDeadlineAt())).param("now", timestamp(now))
                    .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl))).update();
            insertHistory(command.transactionId(), null, "PAYMENT", "T6", "SUCCESS", "REFUNDING",
                    financialVersion, "API", operation.id(), "order-service", null, now);
            insertOutbox(initiatedEventId, command.transactionId(), command.orderId(),
                    "com.ecommerce.payment.refund.initiated.v2", "refund-initiated.schema.json",
                    eventData(command.transactionId(), command.orderId(), integer(payment, "payment_attempt_number"),
                            financialVersion, operation.refundTransactionId(), command.amount().amountMinor(),
                            command.amount().currency(), now, Map.of("reason_code", command.reason().name())), now);
            return new RefundPaymentPreparation.Acquired(operation, commandClaim.token, 0,
                    command.orderId(), integer(payment, "payment_attempt_number"),
                    command.amount().amountMinor(), command.amount().currency(),
                    integer(payment, "version") + 1, financialVersion);
        });
    }

    @Override
    public boolean completeConfirmed(ConfirmedRefund c) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean succeeded = "succeeded".equalsIgnoreCase(c.providerStatus());
            long processingVersion = c.oldFinancialStateVersion() + 1;
            long finalVersion = processingVersion + (succeeded ? 1 : 0);
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET status=:status, financial_state_version=:financial_version,
                           version=version+1, updated_at=:now
                     WHERE id=:id AND status='REFUNDING' AND version=:version
                    """).param("status", succeeded ? "REFUNDED" : "REFUNDING")
                    .param("financial_version", finalVersion).param("now", timestamp(c.completedAt()))
                    .param("id", c.paymentTransactionId()).param("version", c.expectedPaymentVersion()).update();
            int refund = jdbc.sql("""
                    UPDATE refund_transactions
                       SET status=:status,provider_refund_id=:provider_refund_id,
                           provider_status_snapshot=:provider_status,accepted_at=:now,
                           finalized_at=CASE WHEN :succeeded THEN :now ELSE finalized_at END,
                           version=version+:version_increment,updated_at=:now
                     WHERE id=:id AND status='PENDING'
                    """).param("status", succeeded ? "SUCCESS" : "PROCESSING")
                    .param("provider_refund_id", c.providerRefundId()).param("provider_status", c.providerStatus())
                    .param("now", timestamp(c.completedAt())).param("succeeded", succeeded)
                    .param("version_increment", succeeded ? 2 : 1).param("id", c.refundTransactionId()).update();
            int operation = finishOperation(c.operationId(), c.owner(), c.operationLeaseToken(),
                    "PROVIDER_CONFIRMED", c.providerRefundId(), c.providerRequestId(),
                    c.sanitizedProviderResultJson(), c.completedAt());
            int command = completeCommand(c.idempotencyKey(), c.owner(), c.commandLeaseToken(),
                    c.refundTransactionId(), c.paymentTransactionId(), c.completedAt());
            if (payment != 1 || refund != 1 || operation != 1 || command != 1) {
                status.setRollbackOnly(); return false;
            }
            insertHistory(c.paymentTransactionId(), c.refundTransactionId(), "REFUND", "R1",
                    "PENDING", "PROCESSING", processingVersion, "API", c.operationId(),
                    "order-service", c.sanitizedProviderResultJson(), c.completedAt());
            insertOutbox(c.processingEventId(), c.paymentTransactionId(), c.orderId(),
                    "com.ecommerce.payment.refund.processing.v2", "refund-processing.schema.json",
                    eventData(c.paymentTransactionId(), c.orderId(), c.paymentAttemptNumber(), processingVersion,
                            c.refundTransactionId(), c.amountMinor(), c.currency(), c.completedAt(), Map.of()), c.completedAt());
            if (succeeded) {
                insertHistory(c.paymentTransactionId(), c.refundTransactionId(), "REFUND", "R2",
                        "PROCESSING", "SUCCESS", finalVersion, "API", c.operationId(),
                        "order-service", c.sanitizedProviderResultJson(), c.completedAt());
                insertHistory(c.paymentTransactionId(), null, "PAYMENT", "T7", "REFUNDING", "REFUNDED",
                        finalVersion, "API", c.operationId(), "order-service",
                        c.sanitizedProviderResultJson(), c.completedAt());
                postRefundJournal(c);
                insertOutbox(c.successEventId(), c.paymentTransactionId(), c.orderId(),
                        "com.ecommerce.payment.refund.succeeded.v2", "refund-succeeded.schema.json",
                        eventData(c.paymentTransactionId(), c.orderId(), c.paymentAttemptNumber(), finalVersion,
                                c.refundTransactionId(), c.amountMinor(), c.currency(), c.completedAt(), Map.of()), c.completedAt());
            }
            return true;
        }));
    }

    @Override
    public boolean recordUnknown(UnknownRefund u) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status='UNKNOWN',provider_request_id=:request_id,
                           provider_result=CAST(:result AS jsonb),next_retry_at=:next_retry,
                           lease_owner=NULL,lease_expires_at=NULL,updated_at=:now
                     WHERE id=:id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner=:owner AND lease_token=:token
                    """).param("request_id", u.providerRequestId()).param("result", u.sanitizedProviderResultJson())
                    .param("next_retry", timestamp(u.nextRetryAt())).param("now", timestamp(u.recordedAt()))
                    .param("id", u.operationId()).param("owner", u.owner()).param("token", u.operationLeaseToken()).update();
            int command = expireCommandLease(u.idempotencyKey(), u.owner(), u.commandLeaseToken(), u.recordedAt());
            if (operation != 1 || command != 1) status.setRollbackOnly();
            return operation == 1 && command == 1;
        }));
    }

    @Override
    public boolean completeRejected(RejectedRefund r) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            long version = r.oldFinancialStateVersion() + 1;
            int payment = jdbc.sql("""
                    UPDATE payment_transactions SET status='SUCCESS',financial_state_version=:fv,
                           version=version+1,updated_at=:now
                     WHERE id=:id AND status='REFUNDING' AND version=:version
                    """).param("fv", version).param("now", timestamp(r.completedAt()))
                    .param("id", r.paymentTransactionId()).param("version", r.expectedPaymentVersion()).update();
            int refund = jdbc.sql("""
                    UPDATE refund_transactions SET status='FAILED',provider_status_snapshot='rejected',
                           provider_failure_reason='PROVIDER_REJECTED',finalized_at=:now,
                           version=version+1,updated_at=:now WHERE id=:id AND status='PENDING'
                    """).param("now", timestamp(r.completedAt())).param("id", r.refundTransactionId()).update();
            int operation = finishOperation(r.operationId(), r.owner(), r.operationLeaseToken(),
                    "PROVIDER_REJECTED", null, r.providerRequestId(), r.sanitizedProviderResultJson(), r.completedAt());
            int command = completeCommand(r.idempotencyKey(), r.owner(), r.commandLeaseToken(),
                    r.refundTransactionId(), r.paymentTransactionId(), r.completedAt());
            if (payment != 1 || refund != 1 || operation != 1 || command != 1) {
                status.setRollbackOnly(); return false;
            }
            insertHistory(r.paymentTransactionId(), r.refundTransactionId(), "REFUND", "R4",
                    "PENDING", "FAILED", version, "API", r.operationId(), "order-service",
                    r.sanitizedProviderResultJson(), r.completedAt());
            insertHistory(r.paymentTransactionId(), null, "PAYMENT", "T8", "REFUNDING", "SUCCESS",
                    version, "API", r.operationId(), "order-service", r.sanitizedProviderResultJson(), r.completedAt());
            insertOutbox(r.failedEventId(), r.paymentTransactionId(), r.orderId(),
                    "com.ecommerce.payment.refund.failed.v2", "refund-failed.schema.json",
                    eventData(r.paymentTransactionId(), r.orderId(), r.paymentAttemptNumber(), version,
                            r.refundTransactionId(), r.amountMinor(), r.currency(), r.completedAt(),
                            Map.of("error_code", "PROVIDER_REJECTED")), r.completedAt());
            return true;
        }));
    }

    @Override
    public boolean recordReviewRequired(ReviewRefund r) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int operation = finishOperation(r.operationId(), r.owner(), r.operationLeaseToken(),
                    "MANUAL_REVIEW_REQUIRED", null, r.providerRequestId(),
                    r.sanitizedProviderResultJson(), r.recordedAt());
            int payment = jdbc.sql("""
                    UPDATE payment_transactions SET requires_manual_review=TRUE,
                           version=version+1,updated_at=:now
                     WHERE id=:id AND status='REFUNDING'
                    """).param("now", timestamp(r.recordedAt())).param("id", r.paymentTransactionId()).update();
            int review = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,refund_transaction_id,reason_code,case_status,
                       evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:refund_id,:reason,'OPEN',CAST(:evidence AS jsonb),
                            'API',:now)
                    """).param("id", r.reviewCaseId()).param("payment_id", r.paymentTransactionId())
                    .param("refund_id", r.refundTransactionId()).param("reason", r.reasonCode())
                    .param("evidence", write(List.of(Map.of("operation_id", r.operationId(),
                            "provider_result", r.sanitizedProviderResultJson(),
                            "observed_at", r.recordedAt().toString()))))
                    .param("now", timestamp(r.recordedAt())).update();
            int command = expireCommandLease(r.idempotencyKey(), r.owner(),
                    r.commandLeaseToken(), r.recordedAt());
            if (operation != 1 || payment != 1 || review != 1 || command != 1) status.setRollbackOnly();
            return operation == 1 && payment == 1 && review == 1 && command == 1;
        }));
    }

    private CommandClaim claimCommand(RefundPaymentCommand command, String fingerprint, String owner,
                                      Instant now, Duration leaseTtl, Instant expiresAt) {
        int inserted = jdbc.sql("""
                INSERT INTO payment_idempotency
                  (idempotency_key,action,request_fingerprint,status,lease_owner,lease_token,
                   lease_expires_at,last_heartbeat_at,expires_at)
                VALUES (:key,'REFUND',:fingerprint,'IN_PROGRESS',:owner,0,:lease_expires,:now,:expires)
                ON CONFLICT (idempotency_key,action) DO NOTHING
                """).param("key", command.idempotencyKey()).param("fingerprint", fingerprint)
                .param("owner", owner).param("lease_expires", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("expires", timestamp(expiresAt)).update();
        if (inserted == 1) return new CommandClaim(0, null);
        Map<String, Object> row = jdbc.sql("""
                SELECT request_fingerprint,status,resource_id,response_body::text AS response_body,
                       lease_token,lease_expires_at FROM payment_idempotency
                 WHERE idempotency_key=:key AND action='REFUND' FOR UPDATE
                """).param("key", command.idempotencyKey()).query().singleRow();
        if (!fingerprint.equals(row.get("request_fingerprint")))
            return new CommandClaim(-1, new RefundPaymentPreparation.KeyReused());
        if ("COMPLETED".equals(row.get("status"))) {
            String refundId = (String) row.get("resource_id");
            String paymentId = jdbc.sql("SELECT payment_transaction_id FROM refund_transactions WHERE id=:id")
                    .param("id", refundId).query(String.class).single();
            return new CommandClaim(-1, new RefundPaymentPreparation.Replay(refundId, paymentId));
        }
        Instant leaseExpires = instant(row.get("lease_expires_at"));
        if (leaseExpires != null && leaseExpires.isAfter(now))
            return new CommandClaim(-1, new RefundPaymentPreparation.InProgress());
        long token = number(row, "lease_token");
        int claimed = jdbc.sql("""
                UPDATE payment_idempotency SET lease_owner=:owner,lease_token=lease_token+1,
                       lease_expires_at=:lease_expires,last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action='REFUND' AND status='IN_PROGRESS'
                   AND lease_token=:token AND lease_expires_at<=:now
                """).param("owner", owner).param("lease_expires", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("key", command.idempotencyKey()).param("token", token).update();
        return claimed == 1 ? new CommandClaim(token + 1, null)
                : new CommandClaim(-1, new RefundPaymentPreparation.InProgress());
    }

    private RefundPaymentPreparation claimExisting(Map<String, Object> row, long commandToken,
                                                    String owner, Instant now, Duration leaseTtl) {
        ProviderOperationStatus opStatus = ProviderOperationStatus.valueOf((String) row.get("operation_status"));
        if (opStatus.isTerminal())
            return new RefundPaymentPreparation.Replay((String) row.get("refund_id"),
                    (String) row.get("payment_transaction_id"));
        Instant leaseExpires = instant(row.get("lease_expires_at"));
        Instant nextRetry = instant(row.get("next_retry_at"));
        if ((leaseExpires != null && leaseExpires.isAfter(now)) || (nextRetry != null && nextRetry.isAfter(now)))
            return new RefundPaymentPreparation.InProgress();
        long token = number(row, "lease_token");
        int changed = jdbc.sql("""
                UPDATE provider_operations SET lease_owner=:owner,lease_token=lease_token+1,
                       lease_expires_at=:lease_expires,last_heartbeat_at=:now,updated_at=:now
                 WHERE id=:id AND status IN ('REQUESTED','UNKNOWN') AND lease_token=:token
                   AND (lease_expires_at IS NULL OR lease_expires_at<=:now)
                   AND (next_retry_at IS NULL OR next_retry_at<=:now)
                """).param("owner", owner).param("lease_expires", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("id", row.get("operation_id")).param("token", token).update();
        if (changed != 1) return new RefundPaymentPreparation.InProgress();
        ProviderOperation operation = new ProviderOperation((String) row.get("operation_id"),
                (String) row.get("payment_transaction_id"), (String) row.get("refund_id"),
                ProviderOperationType.REFUND, (String) row.get("provider_idempotency_key"),
                null, (String) row.get("request_fingerprint"), opStatus,
                (String) row.get("provider_object_id"), integer(row, "attempt_count"),
                instant(row.get("first_dispatched_at")), instant(row.get("safe_replay_until")),
                instant(row.get("recovery_deadline_at")), nextRetry);
        return new RefundPaymentPreparation.Acquired(operation, commandToken, token + 1,
                number(row, "order_id"), integer(row, "payment_attempt_number"),
                number(row, "amount_minor"), (String) row.get("currency"),
                integer(row, "payment_version"), number(row, "financial_state_version"));
    }

    private int finishOperation(String id, String owner, long token, String status,
                                String objectId, String requestId, String result, Instant now) {
        return jdbc.sql("""
                UPDATE provider_operations SET status=:status,provider_object_id=:object_id,
                       provider_request_id=:request_id,provider_result=CAST(:result AS jsonb),
                       next_retry_at=NULL,lease_owner=NULL,lease_expires_at=NULL,updated_at=:now
                 WHERE id=:id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_owner=:owner AND lease_token=:token
                """).param("status", status).param("object_id", objectId).param("request_id", requestId)
                .param("result", result).param("now", timestamp(now)).param("id", id)
                .param("owner", owner).param("token", token).update();
    }

    private int completeCommand(String key, String owner, long token,
                                String refundId, String paymentId, Instant now) {
        String body = write(Map.of("refund_transaction_id", refundId,
                "payment_transaction_id", paymentId, "status", "PROCESSING"));
        return jdbc.sql("""
                UPDATE payment_idempotency SET status='COMPLETED',resource_id=:resource_id,
                       response_code=200,response_body=CAST(:body AS jsonb),completed_at=:now,
                       lease_owner=NULL,lease_expires_at=NULL
                 WHERE idempotency_key=:key AND action='REFUND' AND status='IN_PROGRESS'
                   AND lease_owner=:owner AND lease_token=:token
                """).param("resource_id", refundId).param("body", body).param("now", timestamp(now))
                .param("key", key).param("owner", owner).param("token", token).update();
    }

    private int expireCommandLease(String key, String owner, long token, Instant now) {
        return jdbc.sql("""
                UPDATE payment_idempotency SET lease_expires_at=:now,last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action='REFUND' AND status='IN_PROGRESS'
                   AND lease_owner=:owner AND lease_token=:token
                """).param("now", timestamp(now)).param("key", key)
                .param("owner", owner).param("token", token).update();
    }

    private void insertHistory(String paymentId, String refundId, String type, String transition,
                               String oldStatus, String newStatus, long version, String triggeredBy,
                               String cause, String actor, String provider, Instant now) {
        jdbc.sql("""
                INSERT INTO payment_status_history
                  (payment_transaction_id,refund_transaction_id,transaction_type,transition_id,
                   old_status,new_status,financial_state_version,triggered_by,cause_ref,actor,
                   provider_response,created_at)
                VALUES (:payment_id,:refund_id,:type,:transition,:old_status,:new_status,
                        :version,:triggered_by,:cause,:actor,CAST(:provider AS jsonb),:now)
                """).param("payment_id", paymentId).param("refund_id", refundId).param("type", type)
                .param("transition", transition).param("old_status", oldStatus).param("new_status", newStatus)
                .param("version", version).param("triggered_by", triggeredBy).param("cause", cause)
                .param("actor", actor).param("provider", provider).param("now", timestamp(now)).update();
    }

    private void insertOutbox(UUID id, String paymentId, long orderId, String type, String schema,
                              Map<String, Object> data, Instant now) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0"); envelope.put("id", id.toString());
        envelope.put("source", "/services/payment"); envelope.put("subject", "payments/" + paymentId);
        envelope.put("type", type); envelope.put("time", now.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", "/contracts/events/payment/v2/schemas/" + schema);
        envelope.put("data", data);
        jdbc.sql("""
                INSERT INTO outbox_events(event_id,aggregate_id,event_type,partition_key,headers,payload,created_at)
                VALUES (:id,:payment_id,:type,:key,CAST(:headers AS jsonb),CAST(:payload AS jsonb),:now)
                """).param("id", id).param("payment_id", paymentId).param("type", type)
                .param("key", Long.toString(orderId)).param("headers", write(Map.of("event_id", id.toString(), "event_type", type)))
                .param("payload", write(envelope)).param("now", timestamp(now)).update();
    }

    private static Map<String, Object> eventData(String paymentId, long orderId, int attempt,
                                                  long version, String refundId, long amount,
                                                  String currency, Instant now, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2); data.put("order_id", Long.toString(orderId));
        data.put("payment_transaction_id", paymentId); data.put("payment_attempt_number", attempt);
        data.put("financial_state_version", version); data.put("occurred_at", now.toString());
        data.put("refund_transaction_id", refundId);
        data.put("refund_amount", Map.of("amount_minor", amount, "currency", currency));
        data.putAll(extra); return data;
    }

    private void postRefundJournal(ConfirmedRefund c) {
        String lines = write(List.of(
                Map.of("account_code", "CUSTOMER_REFUND_CLEARING", "direction", "DEBIT",
                        "amount_minor", c.amountMinor(), "currency", c.currency()),
                Map.of("account_code", "PROVIDER_RECEIVABLE", "direction", "CREDIT",
                        "amount_minor", c.amountMinor(), "currency", c.currency())));
        jdbc.sql("""
                CALL post_financial_journal(CAST(:id AS varchar),CAST(:payment_id AS varchar),
                  CAST(:refund_id AS varchar),CAST(NULL AS varchar),CAST('REFUND' AS varchar),
                  CAST(:balance_id AS varchar),CAST('REFUND' AS varchar),CAST(:source_id AS varchar),
                  CAST(NULL AS varchar),:amount,CAST(:currency AS char(3)),:occurred_at,:observed_at,
                  CAST(:lines AS jsonb))
                """).param("id", c.journalId()).param("payment_id", c.paymentTransactionId())
                .param("refund_id", c.refundTransactionId()).param("balance_id", c.providerBalanceTransactionId())
                .param("source_id", c.providerRefundId()).param("amount", c.amountMinor())
                .param("currency", c.currency()).param("occurred_at", timestamp(c.completedAt()))
                .param("observed_at", timestamp(c.completedAt())).param("lines", lines).update();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static int integer(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private static Instant instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private record CommandClaim(long token, RefundPaymentPreparation result) {}
}

package com.platform.paymentservice.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.CancelPaymentPort;
import com.platform.paymentservice.application.port.out.ConcurrentFinancialMutationException;
import com.platform.paymentservice.application.port.out.CancelPaymentPreparation;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.service.CancelPaymentCommand;
import com.platform.paymentservice.application.service.CancelPaymentResult;
import com.platform.paymentservice.domain.Money;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class JdbcCancelPaymentAdapter implements CancelPaymentPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcCancelPaymentAdapter(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public CancelPaymentPreparation prepareCommand(
            CancelPaymentCommand command, String fingerprint, String operationId,
            String owner, Instant now, Duration leaseTtl, Instant recoveryDeadline) {
        return transactions.execute(status -> {
            CommandClaim claim = claimCommand(command, fingerprint, owner, now, leaseTtl, recoveryDeadline);
            if (claim.result != null) return claim.result;

            List<Map<String, Object>> payments = jdbc.sql("""
                    SELECT id, order_id, payment_attempt_number, amount_minor, currency,
                           provider_transaction_id, status, version, financial_state_version,
                           expires_at
                      FROM payment_transactions WHERE id = :id FOR UPDATE
                    """).param("id", command.transactionId()).query().listOfRows();
            if (payments.isEmpty()) {
                status.setRollbackOnly();
                return new CancelPaymentPreparation.NotFound();
            }
            Map<String, Object> payment = payments.getFirst();
            if (((Number) payment.get("order_id")).longValue() != command.orderId()) {
                status.setRollbackOnly();
                return new CancelPaymentPreparation.OrderMismatch();
            }
            String paymentStatus = (String) payment.get("status");
            if (isNonCaptureTerminal(paymentStatus)) {
                CancelPaymentResult result = new CancelPaymentResult.AlreadyNonCaptured(
                        command.transactionId(), command.orderId(), number(payment, "financial_state_version"));
                completeImmediateCommand(command.idempotencyKey(), owner, claim.token,
                        command.transactionId(), response(result), now);
                return new CancelPaymentPreparation.Immediate(result);
            }
            if (isCaptured(paymentStatus)) {
                CancelPaymentResult result = new CancelPaymentResult.AlreadySucceeded(
                        command.transactionId(), command.orderId(),
                        new Money(number(payment, "amount_minor"), (String) payment.get("currency")),
                        number(payment, "financial_state_version"));
                completeImmediateCommand(command.idempotencyKey(), owner, claim.token,
                        command.transactionId(), response(result), now);
                return new CancelPaymentPreparation.Immediate(result);
            }
            if ("INITIATING".equals(paymentStatus)) {
                expireCommandLease(command.idempotencyKey(), owner, claim.token, now);
                return new CancelPaymentPreparation.ProviderOutcomeUnknown();
            }

            String reason = "ORDER_COMMAND:" + command.reason().name();
            OperationClaim operation = claimOperation(
                    operationId, command.transactionId(), command.idempotencyKey(),
                    (String) payment.get("provider_transaction_id"), reason,
                    owner, now, leaseTtl, recoveryDeadline, true);
            if (operation.operation == null) {
                expireCommandLease(command.idempotencyKey(), owner, claim.token, now);
                return new CancelPaymentPreparation.Immediate(new CancelPaymentResult.CancelPending(
                        command.transactionId(), command.orderId(),
                        number(payment, "financial_state_version")));
            }
            return acquired(operation, claim.token, payment, command.correlationId(), true);
        });
    }

    @Override
    public List<CancelPaymentPreparation.Acquired> claimExpired(
            String owner, Instant now, int batchSize, Duration leaseTtl,
            Duration recoveryWindow, IdGeneratorPort ids) {
        return transactions.execute(ignored -> {
            List<Map<String, Object>> payments = jdbc.sql("""
                    SELECT id, order_id, payment_attempt_number, amount_minor, currency,
                           provider_transaction_id, status, version, financial_state_version,
                           expires_at
                      FROM payment_transactions
                     WHERE status IN ('PENDING','PROCESSING') AND expires_at <= :now
                     ORDER BY expires_at, id
                     FOR UPDATE SKIP LOCKED LIMIT :batch_size
                    """).param("now", timestamp(now)).param("batch_size", batchSize)
                    .query().listOfRows();
            List<CancelPaymentPreparation.Acquired> acquired = new ArrayList<>();
            for (Map<String, Object> payment : payments) {
                String paymentId = (String) payment.get("id");
                OperationClaim operation = claimOperation(
                        ids.nextProviderOperationId(), paymentId, null,
                        (String) payment.get("provider_transaction_id"), "SESSION_EXPIRED",
                        owner, now, leaseTtl, now.plus(recoveryWindow), false);
                if (operation.operation != null) {
                    acquired.add(acquired(operation, -1, payment,
                            "expiration-scanner:" + paymentId, false));
                }
            }
            return List.copyOf(acquired);
        });
    }

    @Override
    public CancelPaymentPreparation prepareDecline(
            DeclineRecord decline, String operationId, String owner, Instant now,
            Duration leaseTtl, Instant recoveryDeadline) {
        return transactions.execute(ignored -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT id, order_id, payment_attempt_number, amount_minor, currency,
                           provider_transaction_id, status, version, financial_state_version,
                           expires_at, decline_count
                      FROM payment_transactions WHERE id=:id FOR UPDATE
                    """).param("id", decline.transactionId()).query().listOfRows();
            if (rows.isEmpty()) return new CancelPaymentPreparation.Ignored();
            Map<String, Object> payment = rows.getFirst();
            String oldStatus = (String) payment.get("status");
            if (!("PENDING".equals(oldStatus) || "PROCESSING".equals(oldStatus)))
                return new CancelPaymentPreparation.Ignored();
            int declineCount = ((Number) payment.get("decline_count")).intValue() + 1;
            int changed = jdbc.sql("""
                    UPDATE payment_transactions
                       SET status='PENDING', last_error_code=:error_code,
                           last_error_message=:error_message, decline_count=:decline_count,
                           provider_status_snapshot='requires_payment_method',
                           last_provider_event_created_at=COALESCE(:event_created_at,last_provider_event_created_at),
                           last_provider_event_id=CASE WHEN :has_provider_event
                                THEN :event_id ELSE last_provider_event_id END,
                           version=version+1, updated_at=:now
                     WHERE id=:id AND status=:old_status AND version=:expected_version
                    """).param("error_code", decline.declineCode())
                    .param("error_message", decline.errorMessage())
                    .param("decline_count", declineCount).param("now", timestamp(now))
                    .param("event_created_at", timestamp(decline.providerEventCreatedAt()))
                    .param("has_provider_event", decline.providerEventCreatedAt() != null)
                    .param("event_id", decline.providerEventId())
                    .param("id", decline.transactionId()).param("old_status", oldStatus)
                    .param("expected_version", ((Number) payment.get("version")).intValue()).update();
            if (changed != 1) throw new IllegalStateException("decline payment fence was lost");
            jdbc.sql("""
                    INSERT INTO payment_status_history
                      (payment_transaction_id,transaction_type,transition_id,old_status,new_status,
                       financial_state_version,triggered_by,cause_ref,actor,provider_response,created_at)
                    VALUES (:payment_id,'PAYMENT','T3a',:old_status,'PENDING',:financial_version,
                            :triggered_by,:cause_ref,:actor,CAST(:provider_response AS jsonb),:created_at)
                    """).param("payment_id", decline.transactionId()).param("old_status", oldStatus)
                    .param("financial_version", number(payment, "financial_state_version"))
                    .param("triggered_by", decline.triggeredBy())
                    .param("cause_ref", decline.auditCauseRef()).param("actor", decline.actor())
                    .param("provider_response", decline.sanitizedProviderResultJson())
                    .param("created_at", timestamp(now)).update();

            if (decline.inboxId() != null) {
                int inbox = jdbc.sql("""
                        UPDATE webhook_logs
                           SET status='PROCESSED',processed_at=:now,processing_error=NULL,
                               lease_owner=NULL,lease_expires_at=NULL,
                               provider_object_id=:provider_object_id,
                               payment_transaction_id=:payment_id
                         WHERE id=:inbox_id AND status='PROCESSING' AND lease_owner=:owner
                           AND lease_token=:lease_token
                        """).param("now", timestamp(now))
                        .param("provider_object_id", payment.get("provider_transaction_id"))
                        .param("payment_id", decline.transactionId()).param("inbox_id", decline.inboxId())
                        .param("owner", owner).param("lease_token", decline.inboxLeaseToken()).update();
                if (inbox != 1) throw new ConcurrentFinancialMutationException(
                        "webhook-inbox", Long.toString(decline.inboxId()));
            }

            boolean overLimit = declineCount > decline.maximumDeclines();
            boolean terminate = decline.intrinsicallyNonRetryable() || overLimit;
            if (!terminate) return new CancelPaymentPreparation.DeclineRecorded(declineCount, false);
            String reason = overLimit ? "DECLINE_LIMIT" : "DECLINED_NON_RETRYABLE";
            String terminalError = overLimit ? "DECLINE_LIMIT_EXCEEDED"
                    : decline.declineCode().toUpperCase(java.util.Locale.ROOT);
            OperationClaim operation = claimOperation(
                    operationId, decline.transactionId(), null,
                    (String) payment.get("provider_transaction_id"), reason,
                    owner, now, leaseTtl, recoveryDeadline, true);
            if (operation.operation == null)
                return new CancelPaymentPreparation.DeclineRecorded(declineCount, true);
            return new CancelPaymentPreparation.Acquired(
                    operation.operation, -1, operation.token,
                    number(payment, "order_id"), ((Number) payment.get("payment_attempt_number")).intValue(),
                    number(payment, "amount_minor"), (String) payment.get("currency"),
                    "PENDING", ((Number) payment.get("version")).intValue() + 1,
                    number(payment, "financial_state_version"), instant(payment.get("expires_at")),
                    decline.providerEventId(), false, operation.reason,
                    (String) payment.get("provider_transaction_id"), terminalError);
        });
    }

    @Override
    public boolean complete(CancelCompletion completion) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean succeeded = "succeeded".equals(completion.providerStatus());
            String target = succeeded ? "SUCCESS" : targetStatus(completion.operationReason());
            String transition = succeeded ? "T2" : transitionId(target);
            long financialVersion = completion.oldFinancialStateVersion() + 1;
            boolean captureAfterOrderCancel = succeeded
                    && completion.operationReason().startsWith("ORDER_COMMAND:");
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET status = :target, provider_status_snapshot = :provider_status,
                           provider_synced_at = :completed_at,
                           provider_charge_id = COALESCE(:charge_id, provider_charge_id),
                           succeeded_at = CASE WHEN :succeeded THEN :completed_at ELSE succeeded_at END,
                           terminal_at = CASE WHEN :succeeded THEN terminal_at ELSE :completed_at END,
                           client_secret_ciphertext = NULL, client_secret_key_id = NULL,
                           financial_state_version = :financial_version,
                           requires_manual_review = CASE WHEN :manual_review THEN TRUE ELSE requires_manual_review END,
                           version = version + 1, updated_at = :completed_at
                     WHERE id = :payment_id AND status = :old_status AND version = :expected_version
                    """).param("target", target).param("provider_status", completion.providerStatus())
                    .param("completed_at", timestamp(completion.completedAt()))
                    .param("charge_id", completion.providerChargeId()).param("succeeded", succeeded)
                    .param("financial_version", financialVersion)
                    .param("manual_review", captureAfterOrderCancel)
                    .param("payment_id", completion.transactionId())
                    .param("old_status", completion.oldStatus())
                    .param("expected_version", completion.expectedPaymentVersion()).update();
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status='PROVIDER_CONFIRMED', provider_object_id=:provider_object_id,
                           provider_result=CAST(:provider_result AS jsonb),
                           provider_request_id=:provider_request_id, next_retry_at=NULL,
                           lease_owner=NULL, lease_expires_at=NULL, updated_at=:completed_at
                     WHERE id=:operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner=:owner AND lease_token=:lease_token
                    """).param("provider_object_id", completion.providerObjectId())
                    .param("provider_result", completion.sanitizedProviderResultJson())
                    .param("provider_request_id", completion.providerRequestId())
                    .param("completed_at", timestamp(completion.completedAt()))
                    .param("operation_id", completion.operationId()).param("owner", completion.owner())
                    .param("lease_token", completion.operationLeaseToken()).update();
            int command = completion.commandRequest()
                    ? completeCommand(completion, target, financialVersion)
                    : 1;
            if (payment != 1 || operation != 1 || command != 1) {
                status.setRollbackOnly();
                return false;
            }

            jdbc.sql("""
                    INSERT INTO payment_status_history
                      (payment_transaction_id, transaction_type, transition_id, old_status,
                       new_status, financial_state_version, triggered_by, cause_ref, actor,
                       provider_response, created_at)
                    VALUES (:payment_id, 'PAYMENT', :transition, :old_status, :new_status,
                            :financial_version, :triggered_by, :cause_ref, :actor,
                            CAST(:provider_response AS jsonb), :created_at)
                    """).param("payment_id", completion.transactionId())
                    .param("transition", transition).param("old_status", completion.oldStatus())
                    .param("new_status", target).param("financial_version", financialVersion)
                    .param("triggered_by", completion.commandRequest() ? "API" : "SCHEDULER")
                    .param("cause_ref", completion.operationId())
                    .param("actor", completion.commandRequest() ? "order-service" : "expiration-scanner")
                    .param("provider_response", completion.sanitizedProviderResultJson())
                    .param("created_at", timestamp(completion.completedAt())).update();

            insertOutbox(completion, target, financialVersion);
            if (captureAfterOrderCancel) insertCaptureAfterCancelReview(completion);
            return true;
        }));
    }

    @Override
    public boolean recordUnknown(UnknownCancel unknown) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status='UNKNOWN', provider_result=CAST(:provider_result AS jsonb),
                           provider_request_id=:provider_request_id, next_retry_at=:next_retry_at,
                           lease_owner=NULL, lease_expires_at=NULL, updated_at=:recorded_at
                     WHERE id=:operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner=:owner AND lease_token=:lease_token
                    """).param("provider_result", unknown.sanitizedProviderResultJson())
                    .param("provider_request_id", unknown.providerRequestId())
                    .param("next_retry_at", timestamp(unknown.nextRetryAt()))
                    .param("recorded_at", timestamp(unknown.recordedAt()))
                    .param("operation_id", unknown.operationId()).param("owner", unknown.owner())
                    .param("lease_token", unknown.operationLeaseToken()).update();
            int command = unknown.commandRequest()
                    ? expireCommandLease(unknown.idempotencyKey(), unknown.owner(),
                    unknown.commandLeaseToken(), unknown.recordedAt()) : 1;
            if (operation != 1 || command != 1) status.setRollbackOnly();
            return operation == 1 && command == 1;
        }));
    }

    @Override
    public boolean recordReviewRequired(ReviewRequiredCancel review) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int operation = jdbc.sql("""
                    UPDATE provider_operations
                       SET status='MANUAL_REVIEW_REQUIRED',
                           provider_result=CAST(:provider_result AS jsonb),
                           provider_request_id=:provider_request_id, next_retry_at=NULL,
                           lease_owner=NULL, lease_expires_at=NULL, updated_at=:recorded_at
                     WHERE id=:operation_id AND status IN ('REQUESTED','UNKNOWN')
                       AND lease_owner=:owner AND lease_token=:lease_token
                    """).param("provider_result", review.sanitizedProviderResultJson())
                    .param("provider_request_id", review.providerRequestId())
                    .param("recorded_at", timestamp(review.recordedAt()))
                    .param("operation_id", review.operationId()).param("owner", review.owner())
                    .param("lease_token", review.operationLeaseToken()).update();
            int payment = jdbc.sql("""
                    UPDATE payment_transactions
                       SET requires_manual_review=TRUE, version=version+1, updated_at=:recorded_at
                     WHERE id=:payment_id AND status IN ('PENDING','PROCESSING')
                    """).param("recorded_at", timestamp(review.recordedAt()))
                    .param("payment_id", review.transactionId()).update();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("operation_id", review.operationId());
            evidence.put("expected_amount_minor", review.expectedAmountMinor());
            evidence.put("expected_currency", review.expectedCurrency());
            evidence.put("provider_amount_minor", review.providerAmountMinor());
            evidence.put("provider_currency", review.providerCurrency());
            evidence.put("expected_provider_transaction_id", review.expectedProviderTransactionId());
            evidence.put("provider_object_id", review.providerObjectId());
            evidence.put("observed_at", review.recordedAt().toString());
            int reviewRow = jdbc.sql("""
                    INSERT INTO payment_review_cases
                      (id,payment_transaction_id,reason_code,case_status,evidence,opened_by,opened_at)
                    VALUES (:id,:payment_id,:reason_code,'OPEN',CAST(:evidence AS jsonb),
                            :opened_by,:opened_at)
                    """).param("id", review.reviewCaseId()).param("payment_id", review.transactionId())
                    .param("reason_code", review.reasonCode()).param("evidence", write(List.of(evidence)))
                    .param("opened_by", review.commandRequest() ? "API" : "SCHEDULER")
                    .param("opened_at", timestamp(review.recordedAt())).update();
            int command = review.commandRequest()
                    ? expireCommandLease(review.idempotencyKey(), review.owner(),
                    review.commandLeaseToken(), review.recordedAt()) : 1;
            if (operation != 1 || payment != 1 || reviewRow != 1 || command != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private CommandClaim claimCommand(CancelPaymentCommand command, String fingerprint,
                                      String owner, Instant now, Duration leaseTtl,
                                      Instant expiresAt) {
        int inserted = jdbc.sql("""
                INSERT INTO payment_idempotency
                  (idempotency_key, action, request_fingerprint, status, lease_owner,
                   lease_token, lease_expires_at, last_heartbeat_at, expires_at)
                VALUES (:key, 'CANCEL', :fingerprint, 'IN_PROGRESS', :owner, 0,
                        :lease_expires_at, :now, :expires_at)
                ON CONFLICT (idempotency_key, action) DO NOTHING
                """).param("key", command.idempotencyKey()).param("fingerprint", fingerprint)
                .param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("expires_at", timestamp(expiresAt)).update();
        if (inserted == 1) return new CommandClaim(0, null);

        Map<String, Object> row = jdbc.sql("""
                SELECT request_fingerprint, status, lease_token, lease_expires_at,
                       resource_id, response_body::text AS response_body
                  FROM payment_idempotency
                 WHERE idempotency_key=:key AND action='CANCEL' FOR UPDATE
                """).param("key", command.idempotencyKey()).query().singleRow();
        if (!fingerprint.equals(row.get("request_fingerprint")))
            return new CommandClaim(-1, new CancelPaymentPreparation.KeyReused());
        if ("COMPLETED".equals(row.get("status"))) {
            return new CommandClaim(-1, new CancelPaymentPreparation.Immediate(
                    replayCompleted((String) row.get("resource_id"), (String) row.get("response_body"))));
        }
        Instant leaseExpiresAt = instant(row.get("lease_expires_at"));
        if (leaseExpiresAt != null && leaseExpiresAt.isAfter(now))
            return new CommandClaim(-1, new CancelPaymentPreparation.InProgress());
        long oldToken = ((Number) row.get("lease_token")).longValue();
        int claimed = jdbc.sql("""
                UPDATE payment_idempotency
                   SET lease_owner=:owner, lease_token=lease_token+1,
                       lease_expires_at=:lease_expires_at, last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action='CANCEL' AND status='IN_PROGRESS'
                   AND lease_token=:old_token AND lease_expires_at <= :now
                """).param("owner", owner).param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("key", command.idempotencyKey())
                .param("old_token", oldToken).update();
        return claimed == 1 ? new CommandClaim(oldToken + 1, null)
                : new CommandClaim(-1, new CancelPaymentPreparation.InProgress());
    }

    private OperationClaim claimOperation(String operationId, String paymentId, String commandKey,
                                          String providerTransactionId, String reason, String owner,
                                          Instant now, Duration leaseTtl, Instant recoveryDeadline,
                                          boolean claimCommandOwned) {
        String providerKey = "pay:cancel:" + paymentId;
        String fingerprint = write(Map.of(
                "provider_transaction_id", providerTransactionId, "reason", reason));
        int inserted = jdbc.sql("""
                INSERT INTO provider_operations
                  (id, payment_transaction_id, operation_type, provider_idempotency_key,
                   command_idempotency_key, request_fingerprint, status,
                   recovery_deadline_at, next_retry_at, lease_owner, lease_token,
                   lease_expires_at, last_heartbeat_at)
                VALUES (:id, :payment_id, 'CANCEL', :provider_key, :command_key,
                        CAST(:fingerprint AS jsonb), 'REQUESTED', :recovery_deadline,
                        :now, :owner, 0, :lease_expires_at, :now)
                ON CONFLICT DO NOTHING
                """).param("id", operationId).param("payment_id", paymentId)
                .param("provider_key", providerKey).param("command_key", commandKey)
                .param("fingerprint", fingerprint).param("recovery_deadline", timestamp(recoveryDeadline))
                .param("now", timestamp(now)).param("owner", owner)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl))).update();
        if (inserted == 1) {
            return new OperationClaim(new ProviderOperation(
                    operationId, paymentId, null, ProviderOperationType.CANCEL,
                    providerKey, commandKey, fingerprint, ProviderOperationStatus.REQUESTED,
                    null, 0, null, null, recoveryDeadline, now), 0, reason);
        }

        Map<String, Object> row = jdbc.sql("""
                SELECT id, payment_transaction_id, provider_idempotency_key,
                       command_idempotency_key, request_fingerprint::text AS request_fingerprint,
                       request_fingerprint->>'reason' AS reason, status, provider_object_id,
                       attempt_count, first_dispatched_at, safe_replay_until,
                       recovery_deadline_at, next_retry_at, lease_token, lease_expires_at
                  FROM provider_operations
                 WHERE payment_transaction_id=:payment_id AND operation_type='CANCEL'
                 FOR UPDATE
                """).param("payment_id", paymentId).query().singleRow();
        ProviderOperation existing = mapOperation(row);
        if (!claimCommandOwned && existing.commandIdempotencyKey() != null)
            return new OperationClaim(null, -1, (String) row.get("reason"));
        if (existing.status().isTerminal()) return new OperationClaim(null, -1, (String) row.get("reason"));
        Instant nextRetryAt = instant(row.get("next_retry_at"));
        Instant leaseExpiresAt = instant(row.get("lease_expires_at"));
        if ((nextRetryAt != null && nextRetryAt.isAfter(now))
                || (leaseExpiresAt != null && leaseExpiresAt.isAfter(now))) {
            return new OperationClaim(null, -1, (String) row.get("reason"));
        }
        long oldToken = ((Number) row.get("lease_token")).longValue();
        int claimed = jdbc.sql("""
                UPDATE provider_operations
                   SET lease_owner=:owner, lease_token=lease_token+1,
                       command_idempotency_key=COALESCE(:command_key, command_idempotency_key),
                       lease_expires_at=:lease_expires_at, last_heartbeat_at=:now,
                       updated_at=:now
                 WHERE id=:id AND status IN ('REQUESTED','UNKNOWN')
                   AND lease_token=:old_token
                   AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
                   AND (next_retry_at IS NULL OR next_retry_at <= :now)
                """).param("owner", owner).param("command_key", commandKey)
                .param("lease_expires_at", timestamp(now.plus(leaseTtl)))
                .param("now", timestamp(now)).param("id", existing.id())
                .param("old_token", oldToken).update();
        ProviderOperation claimedOperation = commandKey == null ? existing : new ProviderOperation(
                existing.id(), existing.paymentTransactionId(), existing.refundTransactionId(),
                existing.type(), existing.providerIdempotencyKey(), commandKey,
                existing.requestFingerprintJson(), existing.status(), existing.providerObjectId(),
                existing.attemptCount(), existing.firstDispatchedAt(), existing.safeReplayUntil(),
                existing.recoveryDeadlineAt(), existing.nextRetryAt());
        return claimed == 1
                ? new OperationClaim(claimedOperation, oldToken + 1, (String) row.get("reason"))
                : new OperationClaim(null, -1, (String) row.get("reason"));
    }

    private CancelPaymentPreparation.Acquired acquired(OperationClaim operation, long commandToken,
                                                        Map<String, Object> payment,
                                                        String correlationId, boolean commandRequest) {
        return new CancelPaymentPreparation.Acquired(
                operation.operation, commandToken, operation.token,
                number(payment, "order_id"), ((Number) payment.get("payment_attempt_number")).intValue(),
                number(payment, "amount_minor"), (String) payment.get("currency"),
                (String) payment.get("status"), ((Number) payment.get("version")).intValue(),
                number(payment, "financial_state_version"), instant(payment.get("expires_at")),
                correlationId, commandRequest, operation.reason,
                (String) payment.get("provider_transaction_id"), null);
    }

    private int completeCommand(CancelCompletion completion, String target, long financialVersion) {
        CancelPaymentResult result = "SUCCESS".equals(target)
                ? new CancelPaymentResult.AlreadySucceeded(completion.transactionId(), completion.orderId(),
                new Money(completion.amountMinor(), completion.currency()), financialVersion)
                : "CANCELED".equals(target)
                ? new CancelPaymentResult.Canceled(
                        completion.transactionId(), completion.orderId(), financialVersion)
                : new CancelPaymentResult.AlreadyNonCaptured(
                        completion.transactionId(), completion.orderId(), financialVersion);
        return jdbc.sql("""
                UPDATE payment_idempotency
                   SET status='COMPLETED', resource_id=:payment_id, response_code=200,
                       response_body=CAST(:response_body AS jsonb), completed_at=:completed_at,
                       lease_owner=NULL, lease_expires_at=NULL, last_heartbeat_at=:completed_at
                 WHERE idempotency_key=:key AND action='CANCEL' AND status='IN_PROGRESS'
                   AND lease_owner=:owner AND lease_token=:lease_token
                """).param("payment_id", completion.transactionId()).param("response_body", response(result))
                .param("completed_at", timestamp(completion.completedAt()))
                .param("key", completion.idempotencyKey()).param("owner", completion.owner())
                .param("lease_token", completion.commandLeaseToken()).update();
    }

    private void completeImmediateCommand(String key, String owner, long token,
                                          String paymentId, String response, Instant now) {
        int changed = jdbc.sql("""
                UPDATE payment_idempotency
                   SET status='COMPLETED', resource_id=:payment_id, response_code=200,
                       response_body=CAST(:response_body AS jsonb), completed_at=:now,
                       lease_owner=NULL, lease_expires_at=NULL, last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action='CANCEL' AND status='IN_PROGRESS'
                   AND lease_owner=:owner AND lease_token=:token
                """).param("payment_id", paymentId).param("response_body", response)
                .param("now", timestamp(now)).param("key", key).param("owner", owner)
                .param("token", token).update();
        if (changed != 1) throw new IllegalStateException("cancel command lease was lost");
    }

    private int expireCommandLease(String key, String owner, long token, Instant now) {
        return jdbc.sql("""
                UPDATE payment_idempotency SET lease_expires_at=:now, last_heartbeat_at=:now
                 WHERE idempotency_key=:key AND action='CANCEL' AND status='IN_PROGRESS'
                   AND lease_owner=:owner AND lease_token=:token
                """).param("now", timestamp(now)).param("key", key)
                .param("owner", owner).param("token", token).update();
    }

    private void insertOutbox(CancelCompletion completion, String target, long financialVersion) {
        String eventType;
        String schema;
        if ("SUCCESS".equals(target)) {
            eventType = "com.ecommerce.payment.transaction.succeeded.v2";
            schema = "/contracts/events/payment/v2/schemas/payment-succeeded.schema.json";
        } else if ("EXPIRED".equals(target)) {
            eventType = "com.ecommerce.payment.transaction.expired.v2";
            schema = "/contracts/events/payment/v2/schemas/payment-expired.schema.json";
        } else if ("FAILED".equals(target)) {
            eventType = "com.ecommerce.payment.transaction.failed.v2";
            schema = "/contracts/events/payment/v2/schemas/payment-failed.schema.json";
        } else {
            eventType = "com.ecommerce.payment.transaction.canceled.v2";
            schema = "/contracts/events/payment/v2/schemas/payment-canceled.schema.json";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(completion.orderId()));
        data.put("payment_transaction_id", completion.transactionId());
        data.put("payment_attempt_number", completion.paymentAttemptNumber());
        data.put("financial_state_version", financialVersion);
        data.put("occurred_at", completion.completedAt().toString());
        data.put("amount", Map.of("amount_minor", completion.amountMinor(), "currency", completion.currency()));
        if ("CANCELED".equals(target)) {
            data.put("reason_code", completion.operationReason().substring("ORDER_COMMAND:".length()));
        } else if ("FAILED".equals(target)) {
            if (completion.terminalErrorCode() == null || completion.terminalErrorCode().isBlank())
                throw new IllegalStateException("FAILED cancel completion requires terminal error code");
            data.put("error_code", completion.terminalErrorCode());
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", completion.eventId().toString());
        event.put("source", "/services/payment");
        event.put("subject", "payments/" + completion.transactionId());
        event.put("type", eventType);
        event.put("time", completion.completedAt().toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", schema);
        if (completion.correlationId() != null) event.put("correlationid", completion.correlationId());
        event.put("data", data);
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("event_id", completion.eventId().toString());
        headers.put("event_type", eventType);
        if (completion.correlationId() != null) headers.put("correlationid", completion.correlationId());
        jdbc.sql("""
                INSERT INTO outbox_events
                  (event_id, aggregate_id, event_type, partition_key, headers, payload, created_at)
                VALUES (:event_id, :aggregate_id, :event_type, :partition_key,
                        CAST(:headers AS jsonb), CAST(:payload AS jsonb), :created_at)
                """).param("event_id", completion.eventId()).param("aggregate_id", completion.transactionId())
                .param("event_type", eventType).param("partition_key", Long.toString(completion.orderId()))
                .param("headers", write(headers)).param("payload", write(event))
                .param("created_at", timestamp(completion.completedAt())).update();
    }

    private void insertCaptureAfterCancelReview(CancelCompletion completion) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("db_status", completion.oldStatus());
        evidence.put("provider_status", completion.providerStatus());
        evidence.put("operation_id", completion.operationId());
        evidence.put("observed_at", completion.completedAt().toString());
        jdbc.sql("""
                INSERT INTO payment_review_cases
                  (id, payment_transaction_id, reason_code, case_status, evidence,
                   opened_by, opened_at)
                VALUES (:id, :payment_id, 'CAPTURE_AFTER_CANCEL', 'OPEN',
                        CAST(:evidence AS jsonb), 'API', :opened_at)
                """).param("id", completion.reviewCaseId()).param("payment_id", completion.transactionId())
                .param("evidence", write(List.of(evidence)))
                .param("opened_at", timestamp(completion.completedAt())).update();
    }

    private CancelPaymentResult replayCompleted(String paymentId, String responseBody) {
        try {
            var node = json.readTree(responseBody);
            long orderId = node.path("order_id").asLong();
            long version = node.path("financial_state_version").asLong();
            return switch (node.path("outcome").asText()) {
                case "CANCELED" -> new CancelPaymentResult.Canceled(paymentId, orderId, version);
                case "ALREADY_NON_CAPTURED" -> new CancelPaymentResult.AlreadyNonCaptured(paymentId, orderId, version);
                case "ALREADY_SUCCEEDED" -> new CancelPaymentResult.AlreadySucceeded(
                        paymentId, orderId, new Money(
                        node.path("amount").path("amount_minor").asLong(),
                        node.path("amount").path("currency").asText()), version);
                default -> throw new IllegalStateException("unsupported stored cancel outcome");
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid stored cancel response", exception);
        }
    }

    private String response(CancelPaymentResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (result instanceof CancelPaymentResult.Canceled value) {
            responseBase(body, value.transactionId(), value.orderId(), "CANCELED", value.financialStateVersion());
        } else if (result instanceof CancelPaymentResult.AlreadyNonCaptured value) {
            responseBase(body, value.transactionId(), value.orderId(), "ALREADY_NON_CAPTURED", value.financialStateVersion());
        } else if (result instanceof CancelPaymentResult.CancelPending value) {
            responseBase(body, value.transactionId(), value.orderId(), "CANCEL_PENDING", value.financialStateVersion());
        } else if (result instanceof CancelPaymentResult.AlreadySucceeded value) {
            responseBase(body, value.transactionId(), value.orderId(), "ALREADY_SUCCEEDED", value.financialStateVersion());
            body.put("amount", Map.of("amount_minor", value.amount().amountMinor(),
                    "currency", value.amount().currency()));
        } else throw new IllegalArgumentException("cancel response is not durable");
        return write(body);
    }

    private static void responseBase(Map<String, Object> body, String transactionId,
                                     long orderId, String outcome, long financialVersion) {
        body.put("transaction_id", transactionId);
        body.put("order_id", Long.toString(orderId));
        body.put("outcome", outcome);
        body.put("financial_state_version", financialVersion);
    }

    private ProviderOperation mapOperation(Map<String, Object> row) {
        return new ProviderOperation(
                (String) row.get("id"), (String) row.get("payment_transaction_id"), null,
                ProviderOperationType.CANCEL, (String) row.get("provider_idempotency_key"),
                (String) row.get("command_idempotency_key"), (String) row.get("request_fingerprint"),
                ProviderOperationStatus.valueOf((String) row.get("status")),
                (String) row.get("provider_object_id"), ((Number) row.get("attempt_count")).intValue(),
                instant(row.get("first_dispatched_at")), instant(row.get("safe_replay_until")),
                instant(row.get("recovery_deadline_at")), instant(row.get("next_retry_at")));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String targetStatus(String reason) {
        if ("SESSION_EXPIRED".equals(reason)) return "EXPIRED";
        if (reason.startsWith("DECLINED_") || "DECLINE_LIMIT".equals(reason)) return "FAILED";
        if (reason.startsWith("ORDER_COMMAND:")) return "CANCELED";
        throw new IllegalStateException("unsupported cancel operation reason " + reason);
    }

    private static String transitionId(String target) {
        return switch (target) { case "FAILED" -> "T3"; case "EXPIRED" -> "T4"; case "CANCELED" -> "T5";
            default -> throw new IllegalArgumentException("not a non-capture target"); };
    }

    private static boolean isNonCaptureTerminal(String status) {
        return "FAILED".equals(status) || "EXPIRED".equals(status) || "CANCELED".equals(status);
    }

    private static boolean isCaptured(String status) {
        return "SUCCESS".equals(status) || "REFUNDING".equals(status) || "REFUNDED".equals(status);
    }

    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant(); }

    private record CommandClaim(long token, CancelPaymentPreparation result) {}
    private record OperationClaim(ProviderOperation operation, long token, String reason) {}
}

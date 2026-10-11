package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.port.out.RefundPaymentPort;
import com.platform.paymentservice.application.port.out.RefundPaymentPreparation;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import com.platform.paymentservice.domain.RefundStateMachine;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.RefundTrigger;
import com.platform.paymentservice.domain.ReturnScopeInspectionEvidence;
import com.platform.paymentservice.domain.ReleasedBeforeDispatchEvidence;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RefundPaymentService {
    private final RefundPaymentPort persistence;
    private final ProviderOperationPort operations;
    private final PaymentProviderPort provider;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final CanonicalFingerprint fingerprints;
    private final ObjectMapper json;
    private final PaymentStateMachine payments = new PaymentStateMachine();
    private final RefundStateMachine refunds = new RefundStateMachine();
    private final Duration leaseTtl;
    private final Duration safeReplayHorizon;
    private final Duration recoveryWindow;
    private final Duration retryDelay;

    public RefundPaymentService(RefundPaymentPort persistence, ProviderOperationPort operations,
                                PaymentProviderPort provider, IdGeneratorPort ids, ClockPort clock,
                                CanonicalFingerprint fingerprints, ObjectMapper json,
                                Duration leaseTtl, Duration safeReplayHorizon,
                                Duration recoveryWindow, Duration retryDelay) {
        this.persistence = Objects.requireNonNull(persistence);
        this.operations = Objects.requireNonNull(operations);
        this.provider = Objects.requireNonNull(provider);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.fingerprints = Objects.requireNonNull(fingerprints);
        this.json = Objects.requireNonNull(json);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.safeReplayHorizon = positive(safeReplayHorizon, "safeReplayHorizon");
        if (safeReplayHorizon.compareTo(Duration.ofHours(24)) >= 0)
            throw new IllegalArgumentException("safeReplayHorizon must be below 24 hours");
        this.recoveryWindow = positive(recoveryWindow, "recoveryWindow");
        this.retryDelay = positive(retryDelay, "retryDelay");
    }

    public RefundPaymentResult refund(RefundPaymentCommand command, String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        Instant now = clock.now();
        String refundId = ids.nextRefundTransactionId();
        String operationId = ids.nextProviderOperationId();
        String commandFingerprint = fingerprints.sha256(new CanonicalCommand(
                IdempotencyAction.REFUND, Map.of("transactionId", command.transactionId()), body(command)));
        ProviderOperation requested = new ProviderOperation(operationId, command.transactionId(), refundId,
                ProviderOperationType.REFUND, "pay:refund:" + refundId, command.idempotencyKey(),
                providerFingerprint(refundId, command), ProviderOperationStatus.REQUESTED,
                null, 0, null, null, now.plus(recoveryWindow), now);
        RefundPaymentPreparation prepared = persistence.prepare(command, commandFingerprint, requested,
                owner, now, leaseTtl, now.plus(recoveryWindow), ids.nextEventId(), evidenceJson(command));
        if (prepared instanceof RefundPaymentPreparation.Replay replay)
            return new RefundPaymentResult.Processing(replay.refundTransactionId(), replay.paymentTransactionId());
        if (prepared instanceof RefundPaymentPreparation.PaymentNotFound) return new RefundPaymentResult.PaymentNotFound();
        if (prepared instanceof RefundPaymentPreparation.OrderMismatch) return new RefundPaymentResult.OrderMismatch();
        if (prepared instanceof RefundPaymentPreparation.PaymentNotSuccess) return new RefundPaymentResult.PaymentNotSuccess();
        if (prepared instanceof RefundPaymentPreparation.AmountExceeded) return new RefundPaymentResult.AmountExceeded();
        if (prepared instanceof RefundPaymentPreparation.OrderRefundAlreadyExists) return new RefundPaymentResult.OrderRefundAlreadyExists();
        if (prepared instanceof RefundPaymentPreparation.KeyReused) return new RefundPaymentResult.IdempotencyKeyReused();
        if (prepared instanceof RefundPaymentPreparation.InProgress) return new RefundPaymentResult.RequestInProgress();

        RefundPaymentPreparation.Acquired acquired = (RefundPaymentPreparation.Acquired) prepared;
        payments.transition(PaymentStatus.SUCCESS, PaymentTrigger.REFUND_REQUEST_ACCEPTED);
        Instant dispatchedAt = clock.now();
        if (!operations.markDispatched(acquired.operation().id(), owner,
                acquired.operationLeaseToken(), dispatchedAt, safeReplayHorizon))
            return new RefundPaymentResult.Fenced();
        boolean beforeHorizon = acquired.operation().safeReplayUntil() == null
                || dispatchedAt.isBefore(acquired.operation().safeReplayUntil());
        ProviderOperationResult result = beforeHorizon
                ? provider.dispatch(acquired.operation())
                : provider.searchBeforeAnyRecreate(acquired.operation());
        if (result instanceof ProviderOperationResult.Confirmed confirmed)
            return confirmed(command, owner, acquired, confirmed);
        if (result instanceof ProviderOperationResult.Unknown unknown) {
            boolean stored = persistence.recordUnknown(new RefundPaymentPort.UnknownRefund(
                    command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                    acquired.operationLeaseToken(), acquired.operation().id(),
                    unknown.providerRequestId(), unknown.resultJson(),
                    clock.now().plus(retryDelay), clock.now()));
            return stored ? new RefundPaymentResult.ProviderOutcomeUnknown() : new RefundPaymentResult.Fenced();
        }
        if (result instanceof ProviderOperationResult.Rejected rejected) {
            refunds.transition(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_REJECTED);
            payments.transition(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_FAILED_CONFIRMED);
            boolean stored = persistence.completeRejected(new RefundPaymentPort.RejectedRefund(
                    command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                    acquired.operationLeaseToken(), acquired.operation().id(), command.transactionId(),
                    acquired.operation().refundTransactionId(), acquired.orderId(), acquired.paymentAttemptNumber(),
                    acquired.amountMinor(), acquired.currency(), acquired.paymentVersion(),
                    acquired.financialStateVersion(), rejected.providerRequestId(), rejected.resultJson(),
                    ids.nextEventId(), clock.now()));
            return stored ? new RefundPaymentResult.Processing(
                    acquired.operation().refundTransactionId(), command.transactionId())
                    : new RefundPaymentResult.Fenced();
        }
        ProviderOperationResult.ReviewRequired review = (ProviderOperationResult.ReviewRequired) result;
        return review(command, owner, acquired, review.providerRequestId(), review.resultJson(),
                "PROVIDER_IDENTITY_UNBOUND");
    }

    private RefundPaymentResult confirmed(RefundPaymentCommand command, String owner,
                                          RefundPaymentPreparation.Acquired acquired,
                                          ProviderOperationResult.Confirmed confirmed) {
        if (confirmed.amountMinor() == null || confirmed.currency() == null
                || confirmed.amountMinor() != acquired.amountMinor()
                || !confirmed.currency().equals(acquired.currency()))
            return review(command, owner, acquired, confirmed.providerRequestId(),
                    confirmed.resultJson(), "AMOUNT_MISMATCH");
        refunds.transition(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_SENT);
        boolean succeeded = "succeeded".equalsIgnoreCase(confirmed.providerStatus());
        if (succeeded) {
            refunds.transition(RefundStatus.PROCESSING, RefundTrigger.REFUND_SUCCESS_CONFIRMED);
            payments.transition(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_SUCCESS_CONFIRMED);
        }
        boolean stored = persistence.completeConfirmed(new RefundPaymentPort.ConfirmedRefund(
                command.idempotencyKey(), owner, acquired.commandLeaseToken(), acquired.operationLeaseToken(),
                acquired.operation().id(), command.transactionId(), acquired.operation().refundTransactionId(),
                acquired.orderId(), acquired.paymentAttemptNumber(), acquired.amountMinor(), acquired.currency(),
                acquired.paymentVersion(), acquired.financialStateVersion(), confirmed.providerObjectId(),
                confirmed.providerRequestId(), confirmed.resultJson(), confirmed.providerStatus(),
                null, ids.nextEventId(), succeeded ? ids.nextEventId() : null,
                succeeded ? ids.nextFinancialJournalId() : null, clock.now()));
        return stored ? new RefundPaymentResult.Processing(
                acquired.operation().refundTransactionId(), command.transactionId())
                : new RefundPaymentResult.Fenced();
    }

    private RefundPaymentResult review(RefundPaymentCommand command, String owner,
                                       RefundPaymentPreparation.Acquired acquired,
                                       String providerRequestId, String resultJson,
                                       String reasonCode) {
        boolean stored = persistence.recordReviewRequired(new RefundPaymentPort.ReviewRefund(
                command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                acquired.operationLeaseToken(), acquired.operation().id(), command.transactionId(),
                acquired.operation().refundTransactionId(), ids.nextReviewCaseId(), providerRequestId,
                resultJson, reasonCode, clock.now()));
        return stored ? new RefundPaymentResult.ProviderOutcomeUnknown()
                : new RefundPaymentResult.Fenced();
    }

    private String evidenceJson(RefundPaymentCommand command) {
        try { return json.writeValueAsString(evidenceBody(command)); }
        catch (JsonProcessingException impossible) { throw new IllegalArgumentException("invalid evidence", impossible); }
    }

    private static Map<String, Object> body(RefundPaymentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", Long.toString(command.orderId()));
        body.put("refund_request_id", command.refundRequestId());
        body.put("amount", Map.of("amount_minor", command.amount().amountMinor(), "currency", command.amount().currency()));
        body.put("reason", command.reason().name());
        body.put("inventory_evidence", evidenceBody(command));
        return body;
    }

    private static Map<String, Object> evidenceBody(RefundPaymentCommand command) {
        if (command.evidence() instanceof ReturnScopeInspectionEvidence evidence) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", "RETURN_SCOPE_INSPECTION_COMPLETED");
            value.put("refund_scope_id", evidence.refundScopeId());
            value.put("expected", evidence.expected());
            value.put("received", evidence.received());
            value.put("passed", evidence.passed());
            value.put("failed", evidence.failed());
            return value;
        }
        ReleasedBeforeDispatchEvidence evidence = (ReleasedBeforeDispatchEvidence) command.evidence();
        return Map.of("type", "COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH",
                "release_reference", evidence.releaseReference());
    }

    private String providerFingerprint(String refundId, RefundPaymentCommand command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider_transaction_id", null);
        value.put("amount_minor", command.amount().amountMinor());
        value.put("currency", command.amount().currency());
        value.put("metadata", Map.of("order_id", Long.toString(command.orderId()),
                "refund_request_id", command.refundRequestId(),
                "refund_transaction_id", refundId,
                "payment_transaction_id", command.transactionId()));
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

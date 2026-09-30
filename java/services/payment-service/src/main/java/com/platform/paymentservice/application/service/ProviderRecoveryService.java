package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.CancelPaymentPort;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.InitiatePaymentPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.port.out.ProviderRecoveryPort;
import com.platform.paymentservice.application.port.out.RefundPaymentPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.RefundStateMachine;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.RefundTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class ProviderRecoveryService {
    private final ProviderRecoveryPort recovery;
    private final ProviderOperationPort operations;
    private final PaymentProviderPort provider;
    private final InitiatePaymentPort initiations;
    private final CancelPaymentPort cancellations;
    private final RefundPaymentPort refunds;
    private final SecretCipherPort cipher;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final Duration leaseTtl;
    private final Duration safeReplayHorizon;
    private final Duration baseBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;
    private final Duration clientSecretMargin;

    public ProviderRecoveryService(ProviderRecoveryPort recovery, ProviderOperationPort operations,
                                   PaymentProviderPort provider, InitiatePaymentPort initiations,
                                   CancelPaymentPort cancellations, RefundPaymentPort refunds,
                                   SecretCipherPort cipher, IdGeneratorPort ids, ClockPort clock,
                                   Duration leaseTtl, Duration safeReplayHorizon,
                                   Duration baseBackoff, Duration maximumBackoff,
                                   int maximumAttempts, Duration clientSecretMargin) {
        this.recovery = Objects.requireNonNull(recovery);
        this.operations = Objects.requireNonNull(operations);
        this.provider = Objects.requireNonNull(provider);
        this.initiations = Objects.requireNonNull(initiations);
        this.cancellations = Objects.requireNonNull(cancellations);
        this.refunds = Objects.requireNonNull(refunds);
        this.cipher = Objects.requireNonNull(cipher);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.safeReplayHorizon = positive(safeReplayHorizon, "safeReplayHorizon");
        if (safeReplayHorizon.compareTo(Duration.ofHours(24)) >= 0)
            throw new IllegalArgumentException("safeReplayHorizon must be below 24 hours");
        this.baseBackoff = positive(baseBackoff, "baseBackoff");
        this.maximumBackoff = positive(maximumBackoff, "maximumBackoff");
        if (maximumBackoff.compareTo(baseBackoff) < 0)
            throw new IllegalArgumentException("maximumBackoff must be at least baseBackoff");
        if (maximumAttempts < 1) throw new IllegalArgumentException("maximumAttempts must be positive");
        this.maximumAttempts = maximumAttempts;
        this.clientSecretMargin = positive(clientSecretMargin, "clientSecretMargin");
    }

    public int recover(String owner, int batchSize) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be 1..1000");
        var claimed = recovery.claimDue(owner, clock.now(), leaseTtl, batchSize);
        for (ProviderRecoveryPort.Claimed item : claimed) recoverOne(owner, item);
        return claimed.size();
    }

    private void recoverOne(String owner, ProviderRecoveryPort.Claimed item) {
        ProviderOperation operation = item.operation();
        Instant now = clock.now();
        if (!now.isBefore(operation.recoveryDeadlineAt()) || operation.attemptCount() >= maximumAttempts) {
            reviewExhausted(owner, item, now);
            return;
        }
        if (!operations.markDispatched(operation.id(), owner, item.operationLeaseToken(),
                now, safeReplayHorizon)) return;
        boolean beforeHorizon = operation.safeReplayUntil() == null
                || now.isBefore(operation.safeReplayUntil());
        ProviderOperationResult result = beforeHorizon
                ? provider.dispatch(operation)
                : provider.searchBeforeAnyRecreate(operation);
        switch (item) {
            case ProviderRecoveryPort.Create create -> finishCreate(owner, create, result);
            case ProviderRecoveryPort.Cancel cancel -> finishCancel(owner, cancel, result);
            case ProviderRecoveryPort.Refund refund -> finishRefund(owner, refund, result);
        }
    }

    private void finishCreate(String owner, ProviderRecoveryPort.Create item,
                              ProviderOperationResult result) {
        ProviderOperation operation = item.operation();
        Instant now = clock.now();
        if (result instanceof ProviderOperationResult.Confirmed confirmed) {
            char[] secret = confirmed.transientClientSecret().orElse(null);
            if (secret == null || secret.length == 0) {
                reviewCreate(owner, item, confirmed.providerRequestId(),
                        "{\"error_code\":\"PROVIDER_IDENTITY_UNBOUND\"}", true, now);
                return;
            }
            try {
                EncryptedSecret encrypted = cipher.encrypt(secret, operation.paymentTransactionId());
                new PaymentStateMachine().transition(PaymentStatus.INITIATING,
                        PaymentTrigger.PROVIDER_INTENT_IDENTIFIED);
                initiations.completeConfirmed(new InitiatePaymentPort.ConfirmedInitiation(
                        operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                        item.operationLeaseToken(), operation.paymentTransactionId(), operation.id(),
                        confirmed.providerObjectId(), confirmed.providerRequestId(), confirmed.resultJson(),
                        encrypted, item.paymentExpiresAt().minus(clientSecretMargin), item.paymentExpiresAt(),
                        item.paymentAttemptNumber(), item.orderId(), item.amountMinor(), operation.id(),
                        ids.nextEventId(), now));
            } finally {
                Arrays.fill(secret, '\0');
            }
            return;
        }
        if (result instanceof ProviderOperationResult.Unknown unknown) {
            initiations.recordUnknown(new InitiatePaymentPort.UnknownInitiation(
                    operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                    item.operationLeaseToken(), operation.id(), unknown.providerRequestId(),
                    unknown.resultJson(), now.plus(backoff(operation)), now));
            return;
        }
        reviewCreate(owner, item, result.providerRequestId(), result.resultJson(),
                result instanceof ProviderOperationResult.ReviewRequired, now);
    }

    private void finishCancel(String owner, ProviderRecoveryPort.Cancel item,
                              ProviderOperationResult result) {
        ProviderOperation operation = item.operation();
        Instant now = clock.now();
        if (result instanceof ProviderOperationResult.Confirmed confirmed
                && ("canceled".equals(confirmed.providerStatus())
                || "succeeded".equals(confirmed.providerStatus()))) {
            boolean identityMismatch = !item.providerTransactionId().equals(confirmed.providerObjectId())
                    || ("succeeded".equals(confirmed.providerStatus())
                    && (confirmed.providerChargeId() == null || confirmed.providerChargeId().isBlank()));
            boolean amountMismatch = confirmed.amountMinor() == null || confirmed.currency() == null
                    || confirmed.amountMinor() != item.amountMinor()
                    || !confirmed.currency().equals(item.currency());
            if (identityMismatch || amountMismatch) {
                cancellations.recordReviewRequired(new CancelPaymentPort.ReviewRequiredCancel(
                        operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                        item.operationLeaseToken(), operation.paymentTransactionId(), operation.id(),
                        ids.nextReviewCaseId(), item.commandRequest(),
                        identityMismatch ? "PROVIDER_IDENTITY_UNBOUND" : "AMOUNT_MISMATCH",
                        confirmed.providerRequestId(), confirmed.resultJson(), item.amountMinor(),
                        item.currency(), confirmed.amountMinor(), confirmed.currency(),
                        item.providerTransactionId(), confirmed.providerObjectId(), now));
                return;
            }
            cancellations.complete(new CancelPaymentPort.CancelCompletion(
                    operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                    item.operationLeaseToken(), operation.paymentTransactionId(), operation.id(),
                    ids.nextReviewCaseId(), operation.id(), item.commandRequest(), item.orderId(),
                    item.paymentAttemptNumber(), item.amountMinor(), item.currency(), item.paymentStatus(),
                    item.paymentVersion(), item.financialStateVersion(), item.operationReason(),
                    confirmed.providerStatus(), confirmed.providerChargeId(), confirmed.providerObjectId(),
                    confirmed.providerRequestId(), confirmed.resultJson(), item.terminalErrorCode(),
                    ids.nextEventId(), now));
            return;
        }
        cancellations.recordUnknown(new CancelPaymentPort.UnknownCancel(
                operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                item.operationLeaseToken(), operation.id(), item.commandRequest(),
                result.providerRequestId(), result.resultJson(), now.plus(backoff(operation)), now));
    }

    private void finishRefund(String owner, ProviderRecoveryPort.Refund item,
                              ProviderOperationResult result) {
        ProviderOperation operation = item.operation();
        Instant now = clock.now();
        if (result instanceof ProviderOperationResult.Confirmed confirmed) {
            if (confirmed.amountMinor() == null || confirmed.currency() == null
                    || confirmed.amountMinor() != item.amountMinor()
                    || !confirmed.currency().equals(item.currency())) {
                reviewRefund(owner, item, confirmed.providerRequestId(), confirmed.resultJson(),
                        "AMOUNT_MISMATCH", now);
                return;
            }
            new RefundStateMachine().transition(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_SENT);
            boolean succeeded = "succeeded".equalsIgnoreCase(confirmed.providerStatus());
            if (succeeded) {
                new RefundStateMachine().transition(RefundStatus.PROCESSING,
                        RefundTrigger.REFUND_SUCCESS_CONFIRMED);
                new PaymentStateMachine().transition(PaymentStatus.REFUNDING,
                        PaymentTrigger.REFUND_SUCCESS_CONFIRMED);
            }
            refunds.completeConfirmed(new RefundPaymentPort.ConfirmedRefund(
                    operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                    item.operationLeaseToken(), operation.id(), operation.paymentTransactionId(),
                    operation.refundTransactionId(), item.orderId(), item.paymentAttemptNumber(),
                    item.amountMinor(), item.currency(), item.paymentVersion(), item.financialStateVersion(),
                    confirmed.providerObjectId(), confirmed.providerRequestId(), confirmed.resultJson(),
                    confirmed.providerStatus(), null, ids.nextEventId(),
                    succeeded ? ids.nextEventId() : null,
                    succeeded ? ids.nextFinancialJournalId() : null, now));
            return;
        }
        if (result instanceof ProviderOperationResult.Rejected rejected) {
            new RefundStateMachine().transition(RefundStatus.PENDING,
                    RefundTrigger.REFUND_REQUEST_REJECTED);
            new PaymentStateMachine().transition(PaymentStatus.REFUNDING,
                    PaymentTrigger.REFUND_FAILED_CONFIRMED);
            refunds.completeRejected(new RefundPaymentPort.RejectedRefund(
                    operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                    item.operationLeaseToken(), operation.id(), operation.paymentTransactionId(),
                    operation.refundTransactionId(), item.orderId(), item.paymentAttemptNumber(),
                    item.amountMinor(), item.currency(), item.paymentVersion(), item.financialStateVersion(),
                    rejected.providerRequestId(), rejected.resultJson(), ids.nextEventId(), now));
            return;
        }
        if (result instanceof ProviderOperationResult.Unknown unknown) {
            refunds.recordUnknown(new RefundPaymentPort.UnknownRefund(
                    operation.commandIdempotencyKey(), owner, item.commandLeaseToken(),
                    item.operationLeaseToken(), operation.id(), unknown.providerRequestId(),
                    unknown.resultJson(), now.plus(backoff(operation)), now));
            return;
        }
        reviewRefund(owner, item, result.providerRequestId(), result.resultJson(),
                "PROVIDER_IDENTITY_UNBOUND", now);
    }

    private void reviewExhausted(String owner, ProviderRecoveryPort.Claimed item, Instant now) {
        String evidence = "{\"error_code\":\"PROVIDER_RECOVERY_EXHAUSTED\"}";
        switch (item) {
            case ProviderRecoveryPort.Create create -> reviewCreate(owner, create, null, evidence, true, now);
            case ProviderRecoveryPort.Cancel cancel -> cancellations.recordReviewRequired(
                    new CancelPaymentPort.ReviewRequiredCancel(cancel.operation().commandIdempotencyKey(),
                            owner, cancel.commandLeaseToken(), cancel.operationLeaseToken(),
                            cancel.operation().paymentTransactionId(), cancel.operation().id(),
                            ids.nextReviewCaseId(), cancel.commandRequest(), "PROVIDER_IDENTITY_UNBOUND",
                            null, evidence, cancel.amountMinor(), cancel.currency(), null, null,
                            cancel.providerTransactionId(), null, now));
            case ProviderRecoveryPort.Refund refund -> reviewRefund(owner, refund, null, evidence,
                    "PROVIDER_IDENTITY_UNBOUND", now);
        }
    }

    private void reviewCreate(String owner, ProviderRecoveryPort.Create item,
                              String requestId, String result, boolean reviewRequired, Instant now) {
        initiations.recordTerminalFailure(new InitiatePaymentPort.TerminalInitiationFailure(
                item.operation().commandIdempotencyKey(), owner, item.commandLeaseToken(),
                item.operationLeaseToken(), item.operation().paymentTransactionId(),
                item.operation().id(), ids.nextReviewCaseId(), item.operation().id(), requestId,
                result, reviewRequired, now));
    }

    private void reviewRefund(String owner, ProviderRecoveryPort.Refund item,
                              String requestId, String result, String reason, Instant now) {
        refunds.recordReviewRequired(new RefundPaymentPort.ReviewRefund(
                item.operation().commandIdempotencyKey(), owner, item.commandLeaseToken(),
                item.operationLeaseToken(), item.operation().id(),
                item.operation().paymentTransactionId(), item.operation().refundTransactionId(),
                ids.nextReviewCaseId(), requestId, result, reason, now));
    }

    private Duration backoff(ProviderOperation operation) {
        long multiplier = 1L << Math.min(operation.attemptCount(), 10);
        Duration raw;
        try { raw = baseBackoff.multipliedBy(multiplier); }
        catch (ArithmeticException overflow) { raw = maximumBackoff; }
        Duration capped = raw.compareTo(maximumBackoff) > 0 ? maximumBackoff : raw;
        int jitterPercent = 90 + Math.floorMod(operation.id().hashCode(), 21);
        return capped.multipliedBy(jitterPercent).dividedBy(100);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

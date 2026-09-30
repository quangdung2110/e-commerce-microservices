package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.CancelPaymentPort;
import com.platform.paymentservice.application.port.out.CancelPaymentPreparation;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class CancelPaymentService {
    private final CancelPaymentPort persistence;
    private final ProviderOperationPort operations;
    private final PaymentProviderPort provider;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final CanonicalFingerprint fingerprints;
    private final Duration leaseTtl;
    private final Duration safeReplayHorizon;
    private final Duration recoveryWindow;
    private final Duration retryDelay;

    public CancelPaymentService(CancelPaymentPort persistence, ProviderOperationPort operations,
                                PaymentProviderPort provider, IdGeneratorPort ids,
                                ClockPort clock, CanonicalFingerprint fingerprints,
                                Duration leaseTtl, Duration safeReplayHorizon,
                                Duration recoveryWindow, Duration retryDelay) {
        this.persistence = Objects.requireNonNull(persistence);
        this.operations = Objects.requireNonNull(operations);
        this.provider = Objects.requireNonNull(provider);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.fingerprints = Objects.requireNonNull(fingerprints);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.safeReplayHorizon = positive(safeReplayHorizon, "safeReplayHorizon");
        if (safeReplayHorizon.compareTo(Duration.ofHours(24)) >= 0)
            throw new IllegalArgumentException("safeReplayHorizon must be below 24 hours");
        this.recoveryWindow = positive(recoveryWindow, "recoveryWindow");
        this.retryDelay = positive(retryDelay, "retryDelay");
    }

    public CancelPaymentResult cancel(CancelPaymentCommand command, String owner) {
        requireOwner(owner);
        Instant now = clock.now();
        String fingerprint = fingerprints.sha256(new CanonicalCommand(
                IdempotencyAction.CANCEL,
                Map.of("transactionId", command.transactionId()),
                Map.of("order_id", Long.toString(command.orderId()),
                        "reason", command.reason().name())));
        CancelPaymentPreparation preparation = persistence.prepareCommand(
                command, fingerprint, ids.nextProviderOperationId(), owner, now,
                leaseTtl, now.plus(recoveryWindow));
        if (preparation instanceof CancelPaymentPreparation.Immediate immediate) return immediate.result();
        if (preparation instanceof CancelPaymentPreparation.InProgress) return new CancelPaymentResult.RequestInProgress();
        if (preparation instanceof CancelPaymentPreparation.KeyReused) return new CancelPaymentResult.IdempotencyKeyReused();
        if (preparation instanceof CancelPaymentPreparation.NotFound) return new CancelPaymentResult.PaymentNotFound();
        if (preparation instanceof CancelPaymentPreparation.OrderMismatch) return new CancelPaymentResult.OrderMismatch();
        if (preparation instanceof CancelPaymentPreparation.ProviderOutcomeUnknown)
            return new CancelPaymentResult.ProviderOutcomeUnknown();
        return executePrepared((CancelPaymentPreparation.Acquired) preparation, owner);
    }

    public int expireDue(String owner, int batchSize) {
        requireOwner(owner);
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        var acquired = persistence.claimExpired(
                owner, clock.now(), batchSize, leaseTtl, recoveryWindow, ids);
        for (CancelPaymentPreparation.Acquired item : acquired) executePrepared(item, owner);
        return acquired.size();
    }

    public CancelPaymentResult executePrepared(CancelPaymentPreparation.Acquired acquired, String owner) {
        Objects.requireNonNull(acquired);
        requireOwner(owner);
        ProviderOperation operation = acquired.operation();
        Instant beforeCall = clock.now();
        if (!operations.markDispatched(operation.id(), owner,
                acquired.operationLeaseToken(), beforeCall, safeReplayHorizon)) {
            return new CancelPaymentResult.Fenced();
        }
        boolean beforeHorizon = operation.safeReplayUntil() == null
                || beforeCall.isBefore(operation.safeReplayUntil());
        ProviderOperationResult result = beforeHorizon
                ? provider.dispatch(operation)
                : provider.searchBeforeAnyRecreate(operation);
        if (result instanceof ProviderOperationResult.Confirmed confirmed
                && ("canceled".equals(confirmed.providerStatus())
                || "succeeded".equals(confirmed.providerStatus()))) {
            boolean identityMismatch = !acquired.providerTransactionId()
                    .equals(confirmed.providerObjectId())
                    || ("succeeded".equals(confirmed.providerStatus())
                    && (confirmed.providerChargeId() == null
                    || confirmed.providerChargeId().isBlank()));
            boolean amountMismatch = confirmed.amountMinor() == null || confirmed.currency() == null
                    || confirmed.amountMinor() != acquired.amountMinor()
                    || !confirmed.currency().equals(acquired.currency());
            if (identityMismatch || amountMismatch) {
                boolean stored = persistence.recordReviewRequired(
                        new CancelPaymentPort.ReviewRequiredCancel(
                                operation.commandIdempotencyKey(), owner,
                                acquired.commandLeaseToken(), acquired.operationLeaseToken(),
                                operation.paymentTransactionId(), operation.id(), ids.nextReviewCaseId(),
                                acquired.commandRequest(), identityMismatch
                                ? "PROVIDER_IDENTITY_UNBOUND" : "AMOUNT_MISMATCH",
                                confirmed.providerRequestId(), confirmed.resultJson(),
                                acquired.amountMinor(), acquired.currency(), confirmed.amountMinor(),
                                confirmed.currency(), acquired.providerTransactionId(),
                                confirmed.providerObjectId(), clock.now()));
                return stored ? new CancelPaymentResult.ProviderOutcomeUnknown()
                        : new CancelPaymentResult.Fenced();
            }
            boolean stored = persistence.complete(new CancelPaymentPort.CancelCompletion(
                    operation.commandIdempotencyKey(), owner, acquired.commandLeaseToken(),
                    acquired.operationLeaseToken(), operation.paymentTransactionId(), operation.id(),
                    ids.nextReviewCaseId(), acquired.correlationId(), acquired.commandRequest(),
                    acquired.orderId(), acquired.paymentAttemptNumber(), acquired.amountMinor(),
                    acquired.currency(), acquired.paymentStatus(), acquired.paymentVersion(),
                    acquired.financialStateVersion(), acquired.operationReason(),
                    confirmed.providerStatus(), confirmed.providerChargeId(),
                    confirmed.providerObjectId(), confirmed.providerRequestId(),
                    confirmed.resultJson(), acquired.terminalErrorCode(),
                    ids.nextEventId(), clock.now()));
            if (!stored) return new CancelPaymentResult.Fenced();
            long version = acquired.financialStateVersion() + 1;
            if ("succeeded".equals(confirmed.providerStatus())) {
                return new CancelPaymentResult.AlreadySucceeded(
                        operation.paymentTransactionId(), acquired.orderId(),
                        new com.platform.paymentservice.domain.Money(
                                acquired.amountMinor(), acquired.currency()), version);
            }
            return acquired.operationReason().startsWith("ORDER_COMMAND:")
                    ? new CancelPaymentResult.Canceled(operation.paymentTransactionId(), acquired.orderId(), version)
                    : new CancelPaymentResult.AlreadyNonCaptured(operation.paymentTransactionId(), acquired.orderId(), version);
        }

        boolean stored = persistence.recordUnknown(new CancelPaymentPort.UnknownCancel(
                operation.commandIdempotencyKey(), owner, acquired.commandLeaseToken(),
                acquired.operationLeaseToken(), operation.id(), acquired.commandRequest(),
                result.providerRequestId(), result.resultJson(),
                clock.now().plus(retryDelay), clock.now()));
        return stored ? new CancelPaymentResult.ProviderOutcomeUnknown()
                : new CancelPaymentResult.Fenced();
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
    }
}

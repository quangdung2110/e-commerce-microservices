package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationClaim;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProviderOperationExecutor {
    public enum Outcome {
        CONFIRMED,
        REJECTED,
        UNKNOWN,
        REVIEW_REQUIRED,
        IN_PROGRESS,
        NOT_DUE,
        CONFLICT,
        ALREADY_TERMINAL,
        FENCED
    }

    public record Execution(Outcome outcome, ProviderOperationResult providerResult) {}

    private static final Duration PROVIDER_KEY_MAXIMUM = Duration.ofHours(24);

    private final ProviderOperationPort operations;
    private final PaymentProviderPort provider;
    private final ClockPort clock;
    private final Duration leaseTtl;
    private final Duration safeReplayHorizon;
    private final Duration retryDelay;
    private final int maxAttempts;

    public ProviderOperationExecutor(ProviderOperationPort operations,
                                     PaymentProviderPort provider,
                                     ClockPort clock,
                                     Duration leaseTtl,
                                     Duration safeReplayHorizon,
                                     Duration retryDelay,
                                     int maxAttempts) {
        this.operations = Objects.requireNonNull(operations);
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.safeReplayHorizon = positive(safeReplayHorizon, "safeReplayHorizon");
        this.retryDelay = positive(retryDelay, "retryDelay");
        if (safeReplayHorizon.compareTo(PROVIDER_KEY_MAXIMUM) >= 0) {
            throw new IllegalArgumentException("safeReplayHorizon must be below 24 hours");
        }
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    public Execution execute(ProviderOperation requested, String owner) {
        Instant claimedAt = clock.now();
        ProviderOperationClaim claim = operations.createOrClaim(requested, owner, claimedAt, leaseTtl);
        if (claim instanceof ProviderOperationClaim.InProgress) return new Execution(Outcome.IN_PROGRESS, null);
        if (claim instanceof ProviderOperationClaim.NotDue) return new Execution(Outcome.NOT_DUE, null);
        if (claim instanceof ProviderOperationClaim.Conflict) return new Execution(Outcome.CONFLICT, null);
        if (claim instanceof ProviderOperationClaim.Terminal) return new Execution(Outcome.ALREADY_TERMINAL, null);

        ProviderOperationClaim.Acquired acquired = (ProviderOperationClaim.Acquired) claim;
        ProviderOperation operation = acquired.operation();
        long token = acquired.leaseToken();
        Instant beforeCall = clock.now();
        if (!beforeCall.isBefore(operation.recoveryDeadlineAt()) || operation.attemptCount() >= maxAttempts) {
            ProviderOperationResult review = new ProviderOperationResult.ReviewRequired(
                    null, "{\"error_code\":\"PROVIDER_RECOVERY_EXHAUSTED\"}");
            return finish(operation, owner, token, review, Outcome.REVIEW_REQUIRED, null);
        }

        if (!operations.markDispatched(operation.id(), owner, token, beforeCall, safeReplayHorizon)) {
            return new Execution(Outcome.FENCED, null);
        }

        boolean beforeHorizon = operation.safeReplayUntil() == null
                || beforeCall.isBefore(operation.safeReplayUntil());
        ProviderOperationResult result = beforeHorizon
                ? provider.dispatch(operation)
                : provider.searchBeforeAnyRecreate(operation);
        Outcome outcome = outcome(result);
        Instant nextRetryAt = result instanceof ProviderOperationResult.Unknown
                ? clock.now().plus(retryDelay) : null;
        return finish(operation, owner, token, result, outcome, nextRetryAt);
    }

    private Execution finish(ProviderOperation operation, String owner, long token,
                             ProviderOperationResult result, Outcome outcome,
                             Instant nextRetryAt) {
        boolean stored = operations.finish(operation.id(), owner, token, result, clock.now(), nextRetryAt);
        return stored ? new Execution(outcome, result) : new Execution(Outcome.FENCED, null);
    }

    private static Outcome outcome(ProviderOperationResult result) {
        if (result instanceof ProviderOperationResult.Confirmed) return Outcome.CONFIRMED;
        if (result instanceof ProviderOperationResult.Rejected) return Outcome.REJECTED;
        if (result instanceof ProviderOperationResult.Unknown) return Outcome.UNKNOWN;
        return Outcome.REVIEW_REQUIRED;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

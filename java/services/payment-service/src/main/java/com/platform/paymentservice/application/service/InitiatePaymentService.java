package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.InitiatePaymentPort;
import com.platform.paymentservice.application.port.out.InitiatePaymentPreparation;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.PaymentTrigger;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class InitiatePaymentService {
    private final InitiatePaymentPort persistence;
    private final ProviderOperationPort operations;
    private final PaymentProviderPort provider;
    private final SecretCipherPort cipher;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final CanonicalFingerprint fingerprints;
    private final PaymentStateMachine stateMachine;
    private final String publishableKey;
    private final Duration sessionTtl;
    private final Duration clientSecretMargin;
    private final Duration leaseTtl;
    private final Duration safeReplayHorizon;
    private final Duration recoveryDeadline;
    private final Duration retryDelay;

    public InitiatePaymentService(
            InitiatePaymentPort persistence,
            ProviderOperationPort operations,
            PaymentProviderPort provider,
            SecretCipherPort cipher,
            IdGeneratorPort ids,
            ClockPort clock,
            CanonicalFingerprint fingerprints,
            PaymentStateMachine stateMachine,
            String publishableKey,
            Duration sessionTtl,
            Duration clientSecretMargin,
            Duration leaseTtl,
            Duration safeReplayHorizon,
            Duration recoveryDeadline,
            Duration retryDelay) {
        this.persistence = Objects.requireNonNull(persistence);
        this.operations = Objects.requireNonNull(operations);
        this.provider = Objects.requireNonNull(provider);
        this.cipher = Objects.requireNonNull(cipher);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.fingerprints = Objects.requireNonNull(fingerprints);
        this.stateMachine = Objects.requireNonNull(stateMachine);
        if (publishableKey == null || publishableKey.isBlank()) {
            throw new IllegalArgumentException("publishableKey is required");
        }
        this.publishableKey = publishableKey;
        this.sessionTtl = positive(sessionTtl, "sessionTtl");
        this.clientSecretMargin = positive(clientSecretMargin, "clientSecretMargin");
        if (clientSecretMargin.compareTo(sessionTtl) >= 0) {
            throw new IllegalArgumentException("clientSecretMargin must be below sessionTtl");
        }
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.safeReplayHorizon = positive(safeReplayHorizon, "safeReplayHorizon");
        if (safeReplayHorizon.compareTo(Duration.ofHours(24)) >= 0) {
            throw new IllegalArgumentException("safeReplayHorizon must be below 24 hours");
        }
        this.recoveryDeadline = positive(recoveryDeadline, "recoveryDeadline");
        this.retryDelay = positive(retryDelay, "retryDelay");
    }

    public InitiatePaymentResult initiate(InitiatePaymentCommand command, String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        Instant now = clock.now();
        Instant expiresAt = now.plus(sessionTtl);
        String transactionId = ids.nextPaymentTransactionId();
        String operationId = ids.nextProviderOperationId();
        String commandFingerprint = fingerprints.sha256(new CanonicalCommand(
                IdempotencyAction.INITIATE, Map.of(), commandBody(command)));
        ProviderOperation requested = new ProviderOperation(
                operationId,
                transactionId,
                null,
                ProviderOperationType.CREATE,
                "pay:create:" + transactionId,
                command.idempotencyKey(),
                providerFingerprint(transactionId, command),
                ProviderOperationStatus.REQUESTED,
                null,
                0,
                null,
                null,
                now.plus(recoveryDeadline),
                now);

        InitiatePaymentPreparation preparation = persistence.prepare(
                command, commandFingerprint, requested, owner, now, leaseTtl,
                expiresAt, expiresAt.plus(Duration.ofHours(24)));
        if (preparation instanceof InitiatePaymentPreparation.InProgress) {
            return new InitiatePaymentResult.RequestInProgress();
        }
        if (preparation instanceof InitiatePaymentPreparation.KeyReused) {
            return new InitiatePaymentResult.IdempotencyKeyReused();
        }
        if (preparation instanceof InitiatePaymentPreparation.AttemptAlreadyExists) {
            return new InitiatePaymentResult.AttemptAlreadyExists();
        }
        if (preparation instanceof InitiatePaymentPreparation.InvalidAttemptSequence) {
            return new InitiatePaymentResult.InvalidAttemptSequence();
        }
        if (preparation instanceof InitiatePaymentPreparation.Recovering recovering) {
            return new InitiatePaymentResult.WithoutClientData(
                    recovering.transactionId(), recovering.paymentAttemptNumber(), PaymentStatus.INITIATING);
        }
        if (preparation instanceof InitiatePaymentPreparation.ProviderRejected) {
            return new InitiatePaymentResult.ProviderRejected();
        }
        if (preparation instanceof InitiatePaymentPreparation.NoPaymentCreated absent) {
            return new InitiatePaymentResult.NoPaymentCreated(absent.transactionId(), absent.providerOperationId(),
                    command.orderId(), command.paymentAttemptNumber());
        }
        if (preparation instanceof InitiatePaymentPreparation.Replay replay) {
            return replay(replay);
        }

        InitiatePaymentPreparation.Acquired acquired = (InitiatePaymentPreparation.Acquired) preparation;
        Instant beforeCall = clock.now();
        if (!operations.markDispatched(acquired.operation().id(), owner,
                acquired.operationLeaseToken(), beforeCall, safeReplayHorizon)) {
            return new InitiatePaymentResult.Fenced();
        }

        boolean beforeHorizon = acquired.operation().safeReplayUntil() == null
                || beforeCall.isBefore(acquired.operation().safeReplayUntil());
        ProviderOperationResult result = beforeHorizon
                ? provider.dispatch(acquired.operation())
                : provider.searchBeforeAnyRecreate(acquired.operation());
        if (result instanceof ProviderOperationResult.Confirmed confirmed) {
            return completeConfirmed(command, owner, acquired, confirmed, acquired.paymentExpiresAt());
        }
        if (result instanceof ProviderOperationResult.Unknown unknown) {
            boolean stored = persistence.recordUnknown(new InitiatePaymentPort.UnknownInitiation(
                    command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                    acquired.operationLeaseToken(), acquired.operation().id(),
                    unknown.providerRequestId(), unknown.resultJson(),
                    clock.now().plus(retryDelay), clock.now()));
            return stored ? new InitiatePaymentResult.ProviderOutcomeUnknown()
                    : new InitiatePaymentResult.Fenced();
        }
        boolean review = result instanceof ProviderOperationResult.ReviewRequired;
        boolean noPaymentCreated = result instanceof ProviderOperationResult.Rejected rejected && rejected.noPaymentCreated()
                && acquired.operation().status() == ProviderOperationStatus.REQUESTED
                && acquired.operation().attemptCount() == 0 && acquired.operation().firstDispatchedAt() == null
                && acquired.operation().providerObjectId() == null;
        boolean stored = persistence.recordTerminalFailure(
                new InitiatePaymentPort.TerminalInitiationFailure(
                        command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                        acquired.operationLeaseToken(), acquired.operation().paymentTransactionId(),
                        acquired.operation().id(), ids.nextReviewCaseId(), command.correlationId(),
                        result.providerRequestId(), result.resultJson(), review, noPaymentCreated, clock.now()));
        if (!stored) return new InitiatePaymentResult.Fenced();
        if (noPaymentCreated) return new InitiatePaymentResult.NoPaymentCreated(
                acquired.operation().paymentTransactionId(), acquired.operation().id(),
                command.orderId(), command.paymentAttemptNumber());
        return review ? new InitiatePaymentResult.ReviewRequired()
                : new InitiatePaymentResult.ProviderRejected();
    }

    private InitiatePaymentResult completeConfirmed(
            InitiatePaymentCommand command,
            String owner,
            InitiatePaymentPreparation.Acquired acquired,
            ProviderOperationResult.Confirmed confirmed,
            Instant expiresAt) {
        char[] clientSecret = confirmed.transientClientSecret().orElse(null);
        if (clientSecret == null || clientSecret.length == 0) {
            return new InitiatePaymentResult.DependencyUnavailable();
        }
        try {
            EncryptedSecret encrypted = cipher.encrypt(clientSecret, acquired.operation().paymentTransactionId());
            Instant completedAt = clock.now();
            Instant clientExpiresAt = expiresAt.minus(clientSecretMargin);
            stateMachine.transition(PaymentStatus.INITIATING, PaymentTrigger.PROVIDER_INTENT_IDENTIFIED);
            boolean stored = persistence.completeConfirmed(new InitiatePaymentPort.ConfirmedInitiation(
                    command.idempotencyKey(), owner, acquired.commandLeaseToken(),
                    acquired.operationLeaseToken(), acquired.operation().paymentTransactionId(),
                    acquired.operation().id(), confirmed.providerObjectId(),
                    confirmed.providerRequestId(), confirmed.resultJson(), encrypted,
                    clientExpiresAt, expiresAt, command.paymentAttemptNumber(), command.orderId(),
                    command.amount().amountMinor(), command.correlationId(),
                    ids.nextEventId(), completedAt));
            if (!stored) return new InitiatePaymentResult.Fenced();
            return new InitiatePaymentResult.Pending(
                    acquired.operation().paymentTransactionId(), command.paymentAttemptNumber(),
                    clientSecret, publishableKey, expiresAt);
        } catch (RuntimeException encryptionOrPersistenceFailure) {
            return new InitiatePaymentResult.DependencyUnavailable();
        } finally {
            Arrays.fill(clientSecret, '\0');
        }
    }

    private InitiatePaymentResult replay(InitiatePaymentPreparation.Replay replay) {
        if (replay.status() != PaymentStatus.PENDING
                || replay.encryptedClientSecret() == null
                || replay.clientExpiresAt() == null
                || !clock.now().isBefore(replay.clientExpiresAt())) {
            return new InitiatePaymentResult.WithoutClientData(
                    replay.transactionId(), replay.paymentAttemptNumber(), replay.status());
        }
        try {
            char[] secret = cipher.decrypt(replay.encryptedClientSecret(), replay.transactionId());
            try {
                return new InitiatePaymentResult.Pending(
                        replay.transactionId(), replay.paymentAttemptNumber(), secret,
                        publishableKey, replay.paymentExpiresAt());
            } finally {
                Arrays.fill(secret, '\0');
            }
        } catch (RuntimeException cannotDecrypt) {
            return new InitiatePaymentResult.WithoutClientData(
                    replay.transactionId(), replay.paymentAttemptNumber(), replay.status());
        }
    }

    private static Map<String, Object> commandBody(InitiatePaymentCommand command) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("amount_minor", command.amount().amountMinor());
        amount.put("currency", command.amount().currency());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", Long.toString(command.orderId()));
        body.put("payment_attempt_number", command.paymentAttemptNumber());
        body.put("amount", amount);
        body.put("user_id", command.userId().toString());
        body.put("return_url", command.returnUrl());
        return body;
    }

    private static String providerFingerprint(String transactionId, InitiatePaymentCommand command) {
        return "{\"amount_minor\":" + command.amount().amountMinor()
                + ",\"currency\":\"VND\",\"metadata\":{"
                + "\"order_id\":\"" + command.orderId() + "\","
                + "\"payment_attempt_number\":\"" + command.paymentAttemptNumber() + "\","
                + "\"payment_transaction_id\":\"" + transactionId + "\","
                + "\"user_id\":\"" + command.userId() + "\"}}";
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

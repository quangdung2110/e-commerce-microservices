package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.application.service.InitiatePaymentCommand;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface InitiatePaymentPort {
    InitiatePaymentPreparation prepare(InitiatePaymentCommand command,
                                       String commandFingerprint,
                                       ProviderOperation operation,
                                       String owner,
                                       Instant now,
                                       Duration leaseTtl,
                                       Instant paymentExpiresAt,
                                       Instant idempotencyExpiresAt);

    boolean completeConfirmed(ConfirmedInitiation completion);

    boolean recordUnknown(UnknownInitiation unknown);

    boolean recordTerminalFailure(TerminalInitiationFailure failure);

    record ConfirmedInitiation(
            String idempotencyKey,
            String owner,
            long commandLeaseToken,
            long operationLeaseToken,
            String transactionId,
            String operationId,
            String providerTransactionId,
            String providerRequestId,
            String sanitizedProviderResultJson,
            EncryptedSecret encryptedClientSecret,
            Instant clientExpiresAt,
            Instant paymentExpiresAt,
            int paymentAttemptNumber,
            long orderId,
            long amountMinor,
            String correlationId,
            UUID eventId,
            Instant completedAt) {}

    record UnknownInitiation(
            String idempotencyKey,
            String owner,
            long commandLeaseToken,
            long operationLeaseToken,
            String operationId,
            String providerRequestId,
            String sanitizedProviderResultJson,
            Instant nextRetryAt,
            Instant recordedAt) {}

    record TerminalInitiationFailure(
            String idempotencyKey,
            String owner,
            long commandLeaseToken,
            long operationLeaseToken,
            String transactionId,
            String operationId,
            String reviewCaseId,
            String correlationId,
            String providerRequestId,
            String sanitizedProviderResultJson,
            boolean reviewRequired,
            Instant recordedAt) {}
}

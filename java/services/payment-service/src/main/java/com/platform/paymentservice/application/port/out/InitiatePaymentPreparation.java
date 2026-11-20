package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Instant;

public sealed interface InitiatePaymentPreparation {
    record Acquired(ProviderOperation operation, long commandLeaseToken,
                    long operationLeaseToken, Instant paymentExpiresAt)
            implements InitiatePaymentPreparation {}

    record Replay(String transactionId, int paymentAttemptNumber, PaymentStatus status,
                  EncryptedSecret encryptedClientSecret,
                  Instant clientExpiresAt,
                  Instant paymentExpiresAt) implements InitiatePaymentPreparation {}

    record Recovering(String transactionId, int paymentAttemptNumber)
            implements InitiatePaymentPreparation {}

    record ProviderRejected(String transactionId) implements InitiatePaymentPreparation {}
    record NoPaymentCreated(String transactionId, String providerOperationId) implements InitiatePaymentPreparation {}

    record InProgress() implements InitiatePaymentPreparation {}
    record KeyReused() implements InitiatePaymentPreparation {}
    record AttemptAlreadyExists() implements InitiatePaymentPreparation {}
    record InvalidAttemptSequence() implements InitiatePaymentPreparation {}
}

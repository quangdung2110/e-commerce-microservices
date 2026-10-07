package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.application.service.CancelPaymentResult;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Instant;

public sealed interface CancelPaymentPreparation {
    record Acquired(ProviderOperation operation, long commandLeaseToken,
                    long operationLeaseToken, long orderId, int paymentAttemptNumber,
                    long amountMinor, String currency, String paymentStatus,
                    int paymentVersion, long financialStateVersion, Instant expiresAt,
                    String correlationId, boolean commandRequest, String operationReason,
                    String providerTransactionId, String terminalErrorCode)
            implements CancelPaymentPreparation {}
    record DeclineRecorded(int declineCount, boolean terminationRequired)
            implements CancelPaymentPreparation {}
    record Ignored() implements CancelPaymentPreparation {}
    record Immediate(CancelPaymentResult result) implements CancelPaymentPreparation {}
    record ProviderOutcomeUnknown() implements CancelPaymentPreparation {}
    record InProgress() implements CancelPaymentPreparation {}
    record KeyReused() implements CancelPaymentPreparation {}
    record NotFound() implements CancelPaymentPreparation {}
    record OrderMismatch() implements CancelPaymentPreparation {}
}

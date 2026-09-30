package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.application.service.RefundPaymentCommand;
import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface RefundPaymentPort {
    RefundPaymentPreparation prepare(RefundPaymentCommand command, String commandFingerprint,
                                     ProviderOperation operation, String owner, Instant now,
                                     Duration leaseTtl, Instant commandExpiresAt,
                                     UUID initiatedEventId, String evidenceJson);

    boolean completeConfirmed(ConfirmedRefund completion);
    boolean recordUnknown(UnknownRefund unknown);
    boolean completeRejected(RejectedRefund rejected);
    boolean recordReviewRequired(ReviewRefund review);

    record ConfirmedRefund(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String operationId, String paymentTransactionId,
            String refundTransactionId, long orderId, int paymentAttemptNumber,
            long amountMinor, String currency, int expectedPaymentVersion,
            long oldFinancialStateVersion, String providerRefundId,
            String providerRequestId, String sanitizedProviderResultJson,
            String providerStatus, String providerBalanceTransactionId,
            UUID processingEventId, UUID successEventId, String journalId,
            Instant completedAt) {}

    record UnknownRefund(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String operationId, String providerRequestId,
            String sanitizedProviderResultJson, Instant nextRetryAt, Instant recordedAt) {}

    record RejectedRefund(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String operationId, String paymentTransactionId,
            String refundTransactionId, long orderId, int paymentAttemptNumber,
            long amountMinor, String currency, int expectedPaymentVersion,
            long oldFinancialStateVersion, String providerRequestId,
            String sanitizedProviderResultJson, UUID failedEventId, Instant completedAt) {}

    record ReviewRefund(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String operationId, String paymentTransactionId,
            String refundTransactionId, String reviewCaseId, String providerRequestId,
            String sanitizedProviderResultJson, String reasonCode, Instant recordedAt) {}
}

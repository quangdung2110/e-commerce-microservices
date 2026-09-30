package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.application.service.CancelPaymentCommand;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CancelPaymentPort {
    CancelPaymentPreparation prepareCommand(
            CancelPaymentCommand command, String fingerprint, String operationId,
            String owner, Instant now, Duration leaseTtl, Instant recoveryDeadline);

    List<CancelPaymentPreparation.Acquired> claimExpired(
            String owner, Instant now, int batchSize, Duration leaseTtl,
            Duration recoveryWindow, IdGeneratorPort ids);

    CancelPaymentPreparation prepareDecline(
            DeclineRecord decline, String operationId, String owner, Instant now,
            Duration leaseTtl, Instant recoveryDeadline);

    boolean complete(CancelCompletion completion);

    boolean recordUnknown(UnknownCancel unknown);

    boolean recordReviewRequired(ReviewRequiredCancel review);

    record CancelCompletion(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String transactionId, String operationId,
            String reviewCaseId, String correlationId, boolean commandRequest,
            long orderId, int paymentAttemptNumber, long amountMinor, String currency,
            String oldStatus, int expectedPaymentVersion, long oldFinancialStateVersion,
            String operationReason, String providerStatus, String providerChargeId,
            String providerObjectId, String providerRequestId, String sanitizedProviderResultJson,
            String terminalErrorCode, UUID eventId, Instant completedAt) {}

    record DeclineRecord(
            String transactionId, String declineCode, String errorMessage,
            String outcomeType, boolean intrinsicallyNonRetryable, int maximumDeclines,
            String providerEventId, String auditCauseRef, String triggeredBy, String actor,
            String sanitizedProviderResultJson,
            Long inboxId, Long inboxLeaseToken, Instant providerEventCreatedAt) {
        public DeclineRecord(String transactionId, String declineCode, String errorMessage,
                             String outcomeType, boolean intrinsicallyNonRetryable,
                             int maximumDeclines, String providerEventId,
                             String sanitizedProviderResultJson, Long inboxId,
                             Long inboxLeaseToken, Instant providerEventCreatedAt) {
            this(transactionId, declineCode, errorMessage, outcomeType,
                    intrinsicallyNonRetryable, maximumDeclines, providerEventId,
                    providerEventId, "WEBHOOK", "stripe", sanitizedProviderResultJson,
                    inboxId, inboxLeaseToken, providerEventCreatedAt);
        }
    }

    record UnknownCancel(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String operationId, boolean commandRequest,
            String providerRequestId, String sanitizedProviderResultJson,
            Instant nextRetryAt, Instant recordedAt) {}

    record ReviewRequiredCancel(
            String idempotencyKey, String owner, long commandLeaseToken,
            long operationLeaseToken, String transactionId, String operationId,
            String reviewCaseId, boolean commandRequest, String reasonCode,
            String providerRequestId, String sanitizedProviderResultJson,
            long expectedAmountMinor, String expectedCurrency,
            Long providerAmountMinor, String providerCurrency,
            String expectedProviderTransactionId, String providerObjectId,
            Instant recordedAt) {}
}

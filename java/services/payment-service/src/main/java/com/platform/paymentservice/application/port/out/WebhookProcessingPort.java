package com.platform.paymentservice.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface WebhookProcessingPort {
    ClaimResult claim(String stripeEventId, String owner, Instant now, Duration leaseTtl);

    List<Claimed> claimDue(String owner, Instant now, Duration leaseTtl, int limit);

    boolean completeIgnored(Claimed claim, String reason, Instant completedAt,
                            String providerObjectId, String paymentId,
                            String refundId, String disputeId);

    boolean completeWithReview(Claimed claim, String paymentId, String providerObjectId,
                               String reasonCode, String evidenceJson,
                               String reviewCaseId, Instant completedAt);

    boolean completeFinancialObjectWithReview(
            Claimed claim, String paymentId, String refundId, String disputeId,
            Integer expectedAggregateVersion, String providerObjectId,
            String reasonCode, String evidenceJson, String reviewCaseId,
            Instant completedAt);

    boolean completeIgnoredAndAdvancePaymentCheckpoint(
            Claimed claim, String paymentId, int expectedPaymentVersion,
            String reason, String providerObjectId, Instant completedAt);

    boolean completeIgnoredAndAdvanceRefundCheckpoint(
            Claimed claim, String refundId, int expectedRefundVersion, String paymentId,
            String reason, String providerObjectId, Instant completedAt);

    boolean completeIgnoredAndAdvanceDisputeCheckpoint(
            Claimed claim, String disputeId, int expectedDisputeVersion, String paymentId,
            String reason, String providerObjectId, Instant completedAt);

    FailureDisposition recordFailure(Claimed claim, String error, Instant failedAt,
                                     Instant nextRetryAt, int maximumAttempts,
                                     String reviewCaseId);

    ReplayResult scheduleReplay(String stripeEventId, String actor, String approver,
                                String causeRef, Instant scheduledAt);

    sealed interface ClaimResult permits Claimed, Busy, Terminal, Missing {}

    record Claimed(long inboxId, String stripeEventId, String eventType,
                   Instant eventCreatedAt, EncryptedSecret encryptedRawBody,
                   String rawBodySha256, int processingAttempts,
                   String owner, long leaseToken, String replayActor,
                   String replayApprover, String replayCauseRef) implements ClaimResult {}

    record Busy() implements ClaimResult {}

    record Terminal(String status) implements ClaimResult {}

    record Missing() implements ClaimResult {}

    enum FailureDisposition { RETRY_SCHEDULED, DEAD, FENCED }

    enum ReplayResult { SCHEDULED, MISSING, PAYLOAD_SHREDDED, BUSY }
}

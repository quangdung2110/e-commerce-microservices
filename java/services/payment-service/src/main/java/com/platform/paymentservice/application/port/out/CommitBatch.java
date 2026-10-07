package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.DisputeFundsState;
import com.platform.paymentservice.domain.DisputeStatus;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CommitBatch(
        PaymentChange payment,
        RefundChange refund,
        DisputeChange dispute,
        List<HistoryRecord> history,
        List<OutboxRecord> outbox,
        ReviewCaseRecord reviewCase,
        InboxCompletion inboxCompletion,
        DisputeCreate disputeCreate,
        List<JournalRecord> journals) {

    public CommitBatch(PaymentChange payment, RefundChange refund, DisputeChange dispute,
                       List<HistoryRecord> history, List<OutboxRecord> outbox,
                       ReviewCaseRecord reviewCase, InboxCompletion inboxCompletion) {
        this(payment, refund, dispute, history, outbox, reviewCase, inboxCompletion,
                null, List.of());
    }

    public CommitBatch {
        if (payment == null && refund == null && dispute == null && disputeCreate == null)
            throw new IllegalArgumentException("at least one aggregate change is required");
        history = List.copyOf(Objects.requireNonNull(history));
        outbox = List.copyOf(Objects.requireNonNull(outbox));
        journals = List.copyOf(Objects.requireNonNull(journals));
        if (history.isEmpty()) throw new IllegalArgumentException("history is required");
    }

    public record PaymentChange(String id, int expectedVersion, PaymentStatus oldStatus,
                                PaymentStatus newStatus, long newFinancialStateVersion,
                                Instant providerEventCreatedAt, String providerEventId,
                                String providerTransactionId, String providerChargeId,
                                Instant succeededAt, Instant terminalAt,
                                boolean clearClientSecret, boolean requiresManualReview,
                                String providerStatusSnapshot, Instant providerSyncedAt) {
        public PaymentChange(String id, int expectedVersion, PaymentStatus oldStatus,
                             PaymentStatus newStatus, long newFinancialStateVersion,
                             Instant providerEventCreatedAt, String providerEventId,
                             String providerTransactionId, String providerChargeId,
                             Instant succeededAt, Instant terminalAt,
                             boolean clearClientSecret, boolean requiresManualReview) {
            this(id, expectedVersion, oldStatus, newStatus, newFinancialStateVersion,
                    providerEventCreatedAt, providerEventId, providerTransactionId,
                    providerChargeId, succeededAt, terminalAt, clearClientSecret,
                    requiresManualReview, null, null);
        }
    }

    public record RefundChange(String id, int expectedVersion, RefundStatus oldStatus,
                               RefundStatus newStatus, Instant providerEventCreatedAt,
                               String providerEventId, String providerRefundId,
                               String failureReason, Instant acceptedAt,
                               Instant finalizedAt, Instant reversedAt,
                               String providerStatusSnapshot, Instant providerSyncedAt) {}

    public record DisputeChange(String id, int expectedVersion, DisputeStatus oldStatus,
                                DisputeStatus newStatus, DisputeFundsState newFundsState,
                                Instant providerEventCreatedAt, String providerEventId,
                                Instant evidenceDueAt, Instant closedAt) {}

    public record DisputeCreate(String id, String paymentId, long orderId,
                                String providerDisputeId, long amountMinor, String currency,
                                String reasonCode, DisputeStatus status,
                                DisputeFundsState fundsState, Instant evidenceDueAt,
                                Instant openedAt, Instant providerEventCreatedAt,
                                String providerEventId) {}

    public record JournalRecord(String id, String paymentId, String refundId,
                                String disputeId, String journalType,
                                String providerBalanceTransactionId,
                                String sourceType, String sourceId,
                                String reversalOfJournalId, long amountMinor,
                                String currency, Instant occurredAt,
                                Instant providerObservedAt, String linesJson) {}

    public record HistoryRecord(String paymentId, String refundId, String disputeId,
                                String transactionType, String transitionId,
                                String oldStatus, String newStatus, long financialStateVersion,
                                String triggeredBy, String causeRef, String actor,
                                String providerResponseJson, Instant createdAt) {}

    public record OutboxRecord(UUID eventId, String aggregateId, String eventType,
                               String partitionKey, String headersJson,
                               String payloadJson, Instant createdAt) {}

    public record ReviewCaseRecord(String id, String paymentId, String refundId,
                                   String reasonCode, String evidenceJson,
                                   String openedBy, Instant openedAt) {}

    public record InboxCompletion(long inboxId, long leaseToken, String terminalStatus,
                                  String processingError, Instant processedAt,
                                  String providerObjectId, String paymentId,
                                  String refundId, String disputeId) {
        public InboxCompletion(long inboxId, long leaseToken, String terminalStatus,
                               String processingError, Instant processedAt) {
            this(inboxId, leaseToken, terminalStatus, processingError, processedAt,
                    null, null, null, null);
        }
    }
}

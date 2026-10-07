package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.DisputeFundsState;
import com.platform.paymentservice.domain.DisputeStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReconciliationPort {
    Optional<Run> start(String lane, Instant windowFrom, Instant windowTo,
                        boolean autoCorrectionFrozen, Instant now);

    boolean currentFreeze(boolean fallback);

    void setFreeze(boolean frozen, String actor, String causeRef, Instant now);

    List<PaymentCandidate> findPayments(Instant windowFrom, Instant windowTo, int limit);

    /**
     * Refunds to reconcile: touched in the window, still PROCESSING, or SUCCESS finalized at or
     * after {@code successSince} (a reversal can arrive ~30 days later). Least recently synced first.
     */
    List<RefundCandidate> findRefunds(Instant windowFrom, Instant windowTo, Instant successSince,
                                      int limit);

    List<DisputeCandidate> findDisputes(Instant windowFrom, Instant windowTo, int limit);

    boolean recordPaymentObservation(String paymentId, int expectedVersion,
                                     String providerStatus, Instant observedAt);

    boolean recordRefundObservation(String refundId, int expectedVersion,
                                    String providerStatus, Instant observedAt);

    boolean openDrift(String paymentId, String refundId, String reviewCaseId,
                      String evidenceJson, Instant openedAt);

    void complete(long runId, String providerCursorTo, Counts counts, Instant finishedAt);

    void fail(long runId, String errorMessage, Instant finishedAt);

    record Run(long id, String lane, Instant windowFrom, Instant windowTo,
               String providerCursorFrom, boolean autoCorrectionFrozen) {}

    record PaymentCandidate(String id, long orderId, int paymentAttemptNumber,
                            long amountMinor, String currency, PaymentStatus status,
                            int version, long financialStateVersion,
                            String providerTransactionId, String providerChargeId) {}

    record RefundCandidate(String id, String paymentId, long orderId,
                           int paymentAttemptNumber, long amountMinor, String currency,
                           RefundStatus status, int version, String providerRefundId,
                           PaymentStatus paymentStatus, int paymentVersion,
                           long financialStateVersion) {}

    record DisputeCandidate(String id, String paymentId, long orderId,
                            int paymentAttemptNumber, long amountMinor, String currency,
                            String reasonCode, DisputeStatus status,
                            DisputeFundsState fundsState, int version,
                            String providerDisputeId, Instant evidenceDueAt,
                            PaymentStatus paymentStatus, int paymentVersion,
                            long financialStateVersion) {}

    record Counts(int scanned, int drift, int corrected, int reviewOpened,
                  long capturedAmountMinor, long refundedAmountMinor,
                  long disputedAmountMinor, long feeAmountMinor,
                  long payoutAmountMinor) {
        public static Counts zero() { return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }
}

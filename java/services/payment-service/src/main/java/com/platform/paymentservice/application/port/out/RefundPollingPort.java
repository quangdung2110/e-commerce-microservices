package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundStatus;

import java.time.Instant;
import java.util.List;

public interface RefundPollingPort {
    List<DueRefund> findDue(Instant updatedBefore, int limit);

    boolean recordObservation(String refundId, int expectedVersion,
                              String providerStatus, Instant observedAt);

    boolean recordReview(DueRefund refund, String reasonCode, String evidenceJson,
                         String reviewCaseId, Instant observedAt);

    record DueRefund(String id, String paymentId, long orderId, int paymentAttemptNumber,
                     long amountMinor, String currency, RefundStatus status, int version,
                     String providerRefundId, PaymentStatus paymentStatus, int paymentVersion,
                     long financialStateVersion) {}
}

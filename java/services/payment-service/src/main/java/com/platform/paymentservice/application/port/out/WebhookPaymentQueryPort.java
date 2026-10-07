package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.ProviderCheckpoint;
import com.platform.paymentservice.domain.RefundStatus;
import com.platform.paymentservice.domain.DisputeStatus;
import com.platform.paymentservice.domain.DisputeFundsState;

import java.time.Instant;
import java.util.Optional;

public interface WebhookPaymentQueryPort {
    Optional<PaymentSnapshot> findByProviderTransactionId(String providerTransactionId);

    Optional<PaymentSnapshot> findByProviderChargeId(String providerChargeId);

    Optional<PaymentSnapshot> findById(String paymentTransactionId);

    Optional<String> findCancelOperationReason(String paymentTransactionId);

    Optional<RefundSnapshot> findRefundByProviderRefundId(String providerRefundId);

    Optional<RefundSnapshot> findRefundById(String refundTransactionId);

    Optional<RefundSnapshot> findRefundByPaymentId(String paymentTransactionId);

    Optional<DisputeSnapshot> findDisputeByProviderDisputeId(String providerDisputeId);

    Optional<DisputeSnapshot> findDisputeById(String disputeTransactionId);

    record PaymentSnapshot(String id, long orderId, int attemptNumber,
                           long amountMinor, String currency, PaymentStatus status,
                           long financialStateVersion, int version,
                           String providerTransactionId, String providerChargeId,
                           String lastErrorCode, ProviderCheckpoint checkpoint) {
    }

    record RefundSnapshot(String id, String paymentId, long orderId, int attemptNumber,
                          long amountMinor, String currency, RefundStatus status, int version,
                          String providerRefundId, ProviderCheckpoint checkpoint,
                          PaymentStatus paymentStatus, int paymentVersion,
                          long financialStateVersion, String providerTransactionId,
                          String providerChargeId) {}

    record DisputeSnapshot(String id, String paymentId, long orderId, int attemptNumber,
                           long amountMinor, String currency, String reasonCode,
                           DisputeStatus status, DisputeFundsState fundsState, int version,
                           Instant evidenceDueAt, ProviderCheckpoint checkpoint,
                           PaymentStatus paymentStatus, int paymentVersion,
                           long financialStateVersion, String providerChargeId) {}
}

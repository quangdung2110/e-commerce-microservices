package com.platform.paymentservice.application.port.out;

import java.util.UUID;

public interface IdGeneratorPort {
    String nextPaymentTransactionId();

    String nextProviderOperationId();

    String nextReviewCaseId();

    default String nextRefundTransactionId() {
        throw new UnsupportedOperationException("refund id generation is not configured");
    }

    default String nextDisputeTransactionId() {
        throw new UnsupportedOperationException("dispute id generation is not configured");
    }

    default String nextFinancialJournalId() {
        throw new UnsupportedOperationException("journal id generation is not configured");
    }

    UUID nextEventId();
}

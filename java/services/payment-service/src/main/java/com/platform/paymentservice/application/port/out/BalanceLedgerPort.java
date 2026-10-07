package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface BalanceLedgerPort {
    Optional<Owner> resolve(String sourceId);

    boolean post(Journal journal);

    record Owner(String paymentId, String refundId, String disputeId) {}

    record Journal(String id, String paymentId, String refundId, String disputeId,
                   String journalType, String providerBalanceTransactionId,
                   String sourceType, String sourceId, long amountMinor,
                   String currency, Instant occurredAt, Instant observedAt,
                   String linesJson) {}
}

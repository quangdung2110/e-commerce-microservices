package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FinancialOperationsQueryPort {
    Optional<Journal> findJournal(String journalId);

    Optional<Dispute> findDispute(String disputeId);

    record Journal(String id, String journalType, long amountMinor, String currency,
                   Instant occurredAt, List<Line> lines) {
        public Journal { lines = List.copyOf(lines); }
    }

    record Line(short lineNo, String accountCode, String direction,
                long amountMinor, String currency) {}

    record Dispute(String id, String paymentId, long orderId, String providerDisputeId,
                   long amountMinor, String currency, String reasonCode, String status,
                   String fundsState, Instant evidenceDueAt, Instant openedAt,
                   Instant closedAt) {}
}

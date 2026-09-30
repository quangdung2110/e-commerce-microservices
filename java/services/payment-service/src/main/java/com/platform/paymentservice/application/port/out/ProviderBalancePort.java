package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.List;

public interface ProviderBalancePort {
    Page list(Instant windowFrom, Instant windowTo, String startingAfter, int limit);

    record Page(List<Entry> entries, String nextCursor, boolean hasMore) {
        public Page { entries = List.copyOf(entries); }
    }

    record Entry(String id, String type, String sourceId, long amountMinor,
                 long feeMinor, long netMinor, String currency, Instant createdAt) {}

    final class ProviderBalanceException extends RuntimeException {
        public ProviderBalanceException(String message, Throwable cause) { super(message, cause); }
    }
}

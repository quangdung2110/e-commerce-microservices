package com.platform.paymentservice.application.port.out;

public final class ConcurrentFinancialMutationException extends RuntimeException {
    public ConcurrentFinancialMutationException(String aggregateType, String id) {
        super("Concurrent or stale " + aggregateType + " mutation for " + id);
    }
}

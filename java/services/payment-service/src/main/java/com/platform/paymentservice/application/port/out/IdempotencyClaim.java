package com.platform.paymentservice.application.port.out;

public sealed interface IdempotencyClaim {
    record Acquired(long leaseToken) implements IdempotencyClaim {}
    record Completed(StoredHttpResponse response) implements IdempotencyClaim {}
    record InProgress() implements IdempotencyClaim {}
    record KeyReused() implements IdempotencyClaim {}
}

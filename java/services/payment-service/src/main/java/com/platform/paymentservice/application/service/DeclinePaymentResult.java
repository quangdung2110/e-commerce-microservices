package com.platform.paymentservice.application.service;

public sealed interface DeclinePaymentResult {
    record RetryableRecorded(int declineCount) implements DeclinePaymentResult {}
    record TerminationPending(int declineCount) implements DeclinePaymentResult {}
    record CancelResolution(CancelPaymentResult result) implements DeclinePaymentResult {}
    record Ignored() implements DeclinePaymentResult {}
}

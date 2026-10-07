package com.platform.paymentservice.domain;

public enum RefundTrigger {
    REFUND_REQUEST_SENT,
    REFUND_SUCCESS_CONFIRMED,
    REFUND_FAILED_CONFIRMED,
    REFUND_REQUEST_REJECTED,
    REFUND_REVERSED_CONFIRMED
}

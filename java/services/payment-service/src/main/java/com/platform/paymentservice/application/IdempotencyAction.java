package com.platform.paymentservice.application;

public enum IdempotencyAction {
    INITIATE, CANCEL, REFUND, REVIEW_CLAIM, REVIEW_RESOLVE, WEBHOOK_REPLAY, RECONCILE, FREEZE
}

package com.platform.paymentservice.domain;

public enum ProviderOperationStatus {
    REQUESTED,
    PROVIDER_CONFIRMED,
    PROVIDER_REJECTED,
    UNKNOWN,
    MANUAL_REVIEW_REQUIRED;

    public boolean isTerminal() {
        return this == PROVIDER_CONFIRMED
                || this == PROVIDER_REJECTED
                || this == MANUAL_REVIEW_REQUIRED;
    }
}

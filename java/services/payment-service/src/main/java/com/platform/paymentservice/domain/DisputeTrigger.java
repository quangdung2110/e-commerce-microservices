package com.platform.paymentservice.domain;

public enum DisputeTrigger {
    DISPUTE_CREATED,
    DISPUTE_UPDATED,
    FUNDS_WITHDRAWN,
    FUNDS_REINSTATED,
    DISPUTE_CLOSED_WON,
    DISPUTE_CLOSED_LOST,
    WARNING_CLOSED
}

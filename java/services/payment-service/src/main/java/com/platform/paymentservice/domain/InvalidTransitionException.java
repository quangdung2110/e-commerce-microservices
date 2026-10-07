package com.platform.paymentservice.domain;

public final class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(Object state, Object trigger) {
        super("Invalid transition from " + state + " using " + trigger);
    }
}

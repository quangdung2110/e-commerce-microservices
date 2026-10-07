package com.ecommerce.checkout.domain;

/**
 * State of a {@code CheckoutSession} (domain.md, "State machine"). {@code EXPIRED} is logical only:
 * a PREVIEWED token past {@code expires_at} is never written as EXPIRED, it is simply refused.
 */
public enum SessionState {
    PREVIEWED,
    SUBMITTING,
    RETRYABLE,
    SUCCEEDED,
    CONFLICT,
    FAILED,
    SUPERSEDED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CONFLICT || this == FAILED;
    }

    /** SUBMITTING and RETRYABLE: a claimed submission that has not reached a terminal result. */
    public boolean isInFlight() {
        return this == SUBMITTING || this == RETRYABLE;
    }

    public static SessionState parse(String value) {
        return SessionState.valueOf(value);
    }
}

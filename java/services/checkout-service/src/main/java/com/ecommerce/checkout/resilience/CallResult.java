package com.ecommerce.checkout.resilience;

import java.util.Optional;

/**
 * Classified result of a dependency call. Never a Reactor error: the caller inspects
 * {@link #outcome()} and decides the public mapping.
 *
 * @param detail            upstream problem code when the provider answered one (for example Order's
 *                          {@code SUBMISSION_IN_PROGRESS}); {@code null} otherwise
 * @param retryAfterSeconds present only for {@link Outcome#CIRCUIT_OPEN}: seconds left until the
 *                          circuit may close, rounded up, at least 1
 */
public record CallResult<T>(Outcome outcome, T value, String detail, Integer retryAfterSeconds) {

    public static <T> CallResult<T> success(T value) {
        return new CallResult<>(Outcome.SUCCESS, value, null, null);
    }

    public static <T> CallResult<T> failure(Outcome outcome) {
        return new CallResult<>(outcome, null, null, null);
    }

    public static <T> CallResult<T> failure(Outcome outcome, String detail) {
        return new CallResult<>(outcome, null, detail, null);
    }

    public static <T> CallResult<T> circuitOpen(int retryAfterSeconds) {
        return new CallResult<>(Outcome.CIRCUIT_OPEN, null, null, retryAfterSeconds);
    }

    /** A call is usable when the provider answered a valid result, business or not. */
    public boolean isUsable() {
        return outcome == Outcome.SUCCESS || outcome == Outcome.BUSINESS_REJECT;
    }

    public Optional<T> valueIfUsable() {
        return isUsable() ? Optional.ofNullable(value) : Optional.empty();
    }
}

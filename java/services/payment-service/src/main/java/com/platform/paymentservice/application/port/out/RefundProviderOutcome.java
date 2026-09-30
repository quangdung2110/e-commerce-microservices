package com.platform.paymentservice.application.port.out;

import java.util.Locale;

/**
 * Canonical reading of a Stripe {@code Refund.status} (ADR-022). Webhook, poller and
 * reconciliation all classify the refund object through this enum instead of the event type,
 * because {@code charge.refunded} carries the charge, not the refund.
 */
public enum RefundProviderOutcome {
    /** Stripe delivered the refund. */
    SUCCEEDED,
    /** {@code failed} or {@code canceled}: the money is back on the merchant balance. */
    FAILED,
    /** Stripe needs customer bank details, or the bank returned the funds to Stripe. */
    REQUIRES_ACTION,
    /** {@code pending} or unknown: not a financial fact. */
    NON_TERMINAL;

    /** Failure reason recorded for a refund that was canceled without a Stripe failure_reason. */
    public static final String CANCELED_DEFAULT_REASON = "merchant_request";

    public static RefundProviderOutcome of(String providerStatus) {
        if (providerStatus == null) return NON_TERMINAL;
        return switch (providerStatus.toLowerCase(Locale.ROOT)) {
            case "succeeded" -> SUCCEEDED;
            case "failed", "canceled" -> FAILED;
            case "requires_action" -> REQUIRES_ACTION;
            default -> NON_TERMINAL;
        };
    }

    /** Stripe attaches failure_reason to canceled refunds; default it when the payload omits it. */
    public static String failureReason(String providerStatus, String failureReason) {
        if (failureReason != null && !failureReason.isBlank()) return failureReason;
        return providerStatus != null && "canceled".equalsIgnoreCase(providerStatus)
                ? CANCELED_DEFAULT_REASON : failureReason;
    }
}

package com.platform.paymentservice.domain;

import java.util.Set;

public final class DeclinePolicy {
    private static final Set<String> NON_RETRYABLE = Set.of(
            "fraudulent", "lost_card", "stolen_card", "pickup_card", "restricted_card",
            "security_violation", "merchant_blacklist");
    private final int maximumDeclines;

    public DeclinePolicy(int maximumDeclines) {
        if (maximumDeclines < 1) throw new IllegalArgumentException("maximumDeclines must be positive");
        this.maximumDeclines = maximumDeclines;
    }

    public boolean mustTerminate(String declineCode, String outcomeType, int declineCountAfterEvent) {
        return "blocked".equals(outcomeType)
                || NON_RETRYABLE.contains(declineCode)
                || declineCountAfterEvent > maximumDeclines;
    }

    public int maximumDeclines() { return maximumDeclines; }
}

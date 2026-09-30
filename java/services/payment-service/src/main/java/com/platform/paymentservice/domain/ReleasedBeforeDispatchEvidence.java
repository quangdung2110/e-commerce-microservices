package com.platform.paymentservice.domain;

public record ReleasedBeforeDispatchEvidence(String releaseReference) implements RefundEvidence {
    public ReleasedBeforeDispatchEvidence {
        if (releaseReference == null || releaseReference.isBlank() || releaseReference.length() > 128)
            throw new IllegalArgumentException("invalid releaseReference");
    }
    @Override public RefundReason reason() { return RefundReason.ADMIN_FORCE_REFUND_BEFORE_DISPATCH; }
}

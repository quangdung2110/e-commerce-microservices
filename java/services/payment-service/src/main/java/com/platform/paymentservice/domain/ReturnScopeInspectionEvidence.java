package com.platform.paymentservice.domain;

public record ReturnScopeInspectionEvidence(
        String refundScopeId, int expected, int received, int passed, int failed) implements RefundEvidence {
    public ReturnScopeInspectionEvidence {
        if (refundScopeId == null || refundScopeId.isBlank() || refundScopeId.length() > 128)
            throw new IllegalArgumentException("invalid refundScopeId");
        if (expected < 0 || received < 0 || passed < 0 || failed < 0)
            throw new IllegalArgumentException("inspection counters must be non-negative");
    }
    @Override public RefundReason reason() { return RefundReason.CUSTOMER_RETURN_SELECTED_LINES; }
}

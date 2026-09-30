package com.platform.paymentservice.domain;

public sealed interface RefundEvidence permits ReturnScopeInspectionEvidence, ReleasedBeforeDispatchEvidence {
    RefundReason reason();
}

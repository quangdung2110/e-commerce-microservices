package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewCasePort {
    List<Case> list(String status, int limit);

    Optional<Case> find(String caseId);

    Mutation claim(String caseId, String actor, Instant at);

    Mutation resolve(String caseId, String actor, String resolutionType,
                     String resolutionNote, String causeRef, Instant at);

    boolean correctionApplied(String caseId);

    enum Mutation { APPLIED, ALREADY_APPLIED, MISSING, CONFLICT, CORRECTION_EVIDENCE_MISSING }

    record Case(String id, String paymentId, String refundId, String reasonCode,
                String status, String evidenceJson, String resolutionType,
                String resolutionNote, Instant openedAt, String assignedTo,
                String resolvedBy, Instant resolvedAt) {}
}

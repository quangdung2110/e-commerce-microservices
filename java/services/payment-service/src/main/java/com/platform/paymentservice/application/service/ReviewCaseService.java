package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.ReviewCasePort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReviewCaseService {
    public enum Outcome { OK, MISSING, CONFLICT, INVALID, IN_PROGRESS, KEY_REUSED, FENCED }
    public record Result(Outcome outcome, ReviewCasePort.Case reviewCase) {}

    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "ESCALATED");
    private static final Set<String> RESOLUTIONS = Set.of("CORRECTION_APPLIED", "NO_ACTION_NEEDED",
            "MANUAL_REFUND_OUTSIDE_SYSTEM", "ESCALATED_TO_ORDER", "ESCALATED_TO_ACCOUNTING");

    private final ReviewCasePort cases;
    private final IdempotencyExecutor idempotency;
    private final ClockPort clock;
    private final ObjectMapper json;
    private final Duration leaseTtl;
    private final Duration retention;

    public ReviewCaseService(ReviewCasePort cases, IdempotencyExecutor idempotency,
                             ClockPort clock, ObjectMapper json,
                             Duration leaseTtl, Duration retention) {
        this.cases = Objects.requireNonNull(cases);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.retention = positive(retention, "retention");
    }

    public List<ReviewCasePort.Case> list(String status, int limit) {
        if (status != null && !STATUSES.contains(status)) throw new IllegalArgumentException("invalid status");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        return cases.list(status, limit);
    }

    public Result claim(String caseId, String actor, String correlationId,
                        String key, String owner) {
        requireIdentifier(caseId, "caseId", 64); requireIdentifier(actor, "actor", 128);
        requireIdentifier(correlationId, "correlationId", 128); requireKey(key);
        CanonicalCommand command = new CanonicalCommand(IdempotencyAction.REVIEW_CLAIM,
                Map.of("caseId", caseId), Map.of("actor", actor));
        var outcome = idempotency.execute(key, IdempotencyAction.REVIEW_CLAIM, command,
                owner, clock.now(), leaseTtl, clock.now().plus(retention), () -> {
                    ReviewCasePort.Mutation mutation = cases.claim(caseId, actor, clock.now());
                    return response(caseId, mutationStatus(mutation), mutation.name());
                });
        return from(outcome, caseId);
    }

    public Result resolve(String caseId, String actor, String approver,
                          String correlationId, String key, String resolution,
                          String note, String causeRef, String owner) {
        requireIdentifier(caseId, "caseId", 64); requireIdentifier(actor, "actor", 128);
        requireIdentifier(approver, "approver", 128); requireIdentifier(correlationId, "correlationId", 128);
        requireKey(key); requireIdentifier(note, "resolutionNote", 2000);
        requireIdentifier(causeRef, "causeRef", 255);
        if (actor.equals(approver)) throw new IllegalArgumentException("maker and checker must differ");
        if (!RESOLUTIONS.contains(resolution)) throw new IllegalArgumentException("invalid resolution");
        CanonicalCommand command = new CanonicalCommand(IdempotencyAction.REVIEW_RESOLVE,
                Map.of("caseId", caseId), Map.of("actor", actor, "approver", approver,
                "resolution_type", resolution, "resolution_note", note, "cause_ref", causeRef));
        var outcome = idempotency.execute(key, IdempotencyAction.REVIEW_RESOLVE, command,
                owner, clock.now(), leaseTtl, clock.now().plus(retention), () -> {
                    ReviewCasePort.Mutation mutation = cases.resolve(caseId, actor, resolution,
                            note, causeRef, clock.now());
                    return response(caseId, mutationStatus(mutation), mutation.name());
                });
        return from(outcome, caseId);
    }

    private Result from(IdempotencyExecutor.Result result, String caseId) {
        return switch (result.outcome()) {
            case IN_PROGRESS -> new Result(Outcome.IN_PROGRESS, null);
            case KEY_REUSED -> new Result(Outcome.KEY_REUSED, null);
            case FENCED -> new Result(Outcome.FENCED, null);
            case EXECUTED, REPLAYED -> {
                int status = result.response().statusCode();
                Outcome outcome = status == 200 ? Outcome.OK : status == 404 ? Outcome.MISSING
                        : status == 422 ? Outcome.INVALID : Outcome.CONFLICT;
                yield new Result(outcome, outcome == Outcome.OK
                        ? cases.find(caseId).orElse(null) : null);
            }
        };
    }

    private StoredHttpResponse response(String caseId, int status, String outcome) {
        try {
            return new StoredHttpResponse(caseId, status, json.writeValueAsString(
                    Map.of("case_id", caseId, "outcome", outcome)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int mutationStatus(ReviewCasePort.Mutation mutation) {
        return switch (mutation) {
            case APPLIED, ALREADY_APPLIED -> 200;
            case MISSING -> 404;
            case CORRECTION_EVIDENCE_MISSING -> 422;
            case CONFLICT -> 409;
        };
    }

    private static void requireKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 255)
            throw new IllegalArgumentException("invalid idempotency key");
    }

    private static void requireIdentifier(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("invalid " + name);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

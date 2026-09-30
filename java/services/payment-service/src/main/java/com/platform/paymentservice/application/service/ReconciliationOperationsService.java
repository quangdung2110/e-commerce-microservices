package com.platform.paymentservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class ReconciliationOperationsService {
    public enum Outcome { ACCEPTED, COMPLETED, ALREADY_RUNNING, FAILED,
        IN_PROGRESS, KEY_REUSED, FENCED }
    public record Result(Outcome outcome, String body) {}
    public record StartCommand(String lane, Instant windowFrom, Instant windowTo,
                               String actor, String approver, String correlationId,
                               String idempotencyKey) {}
    public record FreezeCommand(boolean frozen, String causeRef, String actor,
                                String approver, String correlationId,
                                String idempotencyKey) {}

    private final ReconciliationService objectState;
    private final BalanceReconciliationService balanceLedger;
    private final ReconciliationPort runs;
    private final IdempotencyExecutor idempotency;
    private final ClockPort clock;
    private final ObjectMapper json;
    private final Duration leaseTtl;
    private final Duration retention;
    private final int maximumBatchSize;
    private final boolean defaultFrozen;

    public ReconciliationOperationsService(ReconciliationService objectState,
                                           BalanceReconciliationService balanceLedger,
                                           ReconciliationPort runs,
                                           IdempotencyExecutor idempotency,
                                           ClockPort clock, ObjectMapper json,
                                           Duration leaseTtl, Duration retention,
                                           int maximumBatchSize, boolean defaultFrozen) {
        this.objectState = Objects.requireNonNull(objectState);
        this.balanceLedger = Objects.requireNonNull(balanceLedger);
        this.runs = Objects.requireNonNull(runs);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.retention = positive(retention, "retention");
        if (maximumBatchSize < 1 || maximumBatchSize > 1000)
            throw new IllegalArgumentException("maximumBatchSize must be 1..1000");
        this.maximumBatchSize = maximumBatchSize;
        this.defaultFrozen = defaultFrozen;
    }

    public Result start(StartCommand command, String owner) {
        validateActors(command.actor(), command.approver(), command.correlationId(),
                command.idempotencyKey());
        if (!"OBJECT_STATE".equals(command.lane()) && !"BALANCE_LEDGER".equals(command.lane()))
            throw new IllegalArgumentException("invalid reconciliation lane");
        if (command.windowFrom() == null || command.windowTo() == null
                || !command.windowFrom().isBefore(command.windowTo()))
            throw new IllegalArgumentException("invalid reconciliation window");
        CanonicalCommand canonical = new CanonicalCommand(IdempotencyAction.RECONCILE,
                Map.of("lane", command.lane()), Map.of(
                "window_from", command.windowFrom().toString(),
                "window_to", command.windowTo().toString(),
                "actor", command.actor(), "approver", command.approver()));
        Instant now = clock.now();
        var result = idempotency.execute(command.idempotencyKey(), IdempotencyAction.RECONCILE,
                canonical, owner, now, leaseTtl, now.plus(retention), () -> execute(command));
        return from(result);
    }

    public Result freeze(FreezeCommand command, String owner) {
        validateActors(command.actor(), command.approver(), command.correlationId(),
                command.idempotencyKey());
        require(command.causeRef(), "causeRef", 255);
        CanonicalCommand canonical = new CanonicalCommand(IdempotencyAction.FREEZE,
                Map.of("control", "reconciliation-freeze"), Map.of(
                "frozen", command.frozen(), "cause_ref", command.causeRef(),
                "actor", command.actor(), "approver", command.approver()));
        Instant now = clock.now();
        var result = idempotency.execute(command.idempotencyKey(), IdempotencyAction.FREEZE,
                canonical, owner, now, leaseTtl, now.plus(retention), () -> {
                    runs.setFreeze(command.frozen(), command.actor(), command.causeRef(), clock.now());
                    return response("reconciliation-freeze", 200, Map.of(
                            "frozen", command.frozen(), "cause_ref", command.causeRef()));
                });
        return from(result);
    }

    private StoredHttpResponse execute(StartCommand command) {
        boolean frozen = runs.currentFreeze(defaultFrozen);
        ReconciliationService.Outcome outcome = "OBJECT_STATE".equals(command.lane())
                ? objectState.runObjectState(command.windowFrom(), command.windowTo(),
                frozen, maximumBatchSize)
                : balanceLedger.run(command.windowFrom(), command.windowTo(),
                frozen, maximumBatchSize);
        int status = switch (outcome) {
            case COMPLETED -> 202;
            case ALREADY_RUNNING -> 409;
            case FAILED -> 503;
        };
        return response("reconciliation", status,
                Map.of("lane", command.lane(), "outcome", outcome.name()));
    }

    private Result from(IdempotencyExecutor.Result result) {
        return switch (result.outcome()) {
            case IN_PROGRESS -> new Result(Outcome.IN_PROGRESS, null);
            case KEY_REUSED -> new Result(Outcome.KEY_REUSED, null);
            case FENCED -> new Result(Outcome.FENCED, null);
            case EXECUTED, REPLAYED -> {
                int status = result.response().statusCode();
                Outcome outcome = status == 202 ? Outcome.ACCEPTED
                        : status == 200 ? Outcome.COMPLETED
                        : status == 409 ? Outcome.ALREADY_RUNNING : Outcome.FAILED;
                yield new Result(outcome, result.response().bodyJson());
            }
        };
    }

    private StoredHttpResponse response(String resourceId, int status, Map<String, Object> body) {
        try {
            return new StoredHttpResponse(resourceId, status, json.writeValueAsString(body));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateActors(String actor, String approver,
                                       String correlationId, String key) {
        require(actor, "actor", 128); require(approver, "approver", 128);
        require(correlationId, "correlationId", 128);
        if (actor.equals(approver)) throw new IllegalArgumentException("maker and checker must differ");
        if (key == null || key.length() < 16 || key.length() > 255)
            throw new IllegalArgumentException("invalid idempotency key");
    }

    private static void require(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("invalid " + name);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

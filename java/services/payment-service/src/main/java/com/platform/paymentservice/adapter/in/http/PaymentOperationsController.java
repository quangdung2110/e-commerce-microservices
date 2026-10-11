package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.paymentservice.application.service.WebhookReplayCommand;
import com.platform.paymentservice.application.service.WebhookReplayResult;
import com.platform.paymentservice.application.service.WebhookReplayService;
import com.platform.paymentservice.application.service.ReviewCaseService;
import com.platform.paymentservice.application.service.ManualCorrectionService;
import com.platform.paymentservice.application.service.ReconciliationOperationsService;
import com.platform.paymentservice.application.port.out.ReviewCasePort;
import com.platform.paymentservice.application.port.out.FinancialOperationsQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payment")
final class PaymentOperationsController {
    private final ObjectProvider<WebhookReplayService> replays;
    private final ObjectProvider<ReviewCaseService> reviews;
    private final ObjectProvider<ManualCorrectionService> corrections;
    private final ObjectProvider<ReconciliationOperationsService> reconciliation;
    private final FinancialOperationsQueryPort financialQueries;

    PaymentOperationsController(ObjectProvider<WebhookReplayService> replays,
                                ObjectProvider<ReviewCaseService> reviews,
                                ObjectProvider<ManualCorrectionService> corrections,
                                ObjectProvider<ReconciliationOperationsService> reconciliation,
                                FinancialOperationsQueryPort financialQueries) {
        this.replays = replays;
        this.reviews = reviews;
        this.corrections = corrections;
        this.reconciliation = reconciliation;
        this.financialQueries = financialQueries;
    }

    @GetMapping("/review-cases")
    ResponseEntity<?> listReviewCases(
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        if (actor == null || actor.isBlank() || correlationId == null || correlationId.isBlank())
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        ReviewCaseService service = reviews.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            List<ReviewCaseResponse> body = service.list(status, limit).stream()
                    .map(ReviewCaseResponse::from).toList();
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Correlation-Id", correlationId).body(body);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @PostMapping("/review-cases/{caseId}/claim")
    ResponseEntity<?> claimReviewCase(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        ReviewCaseService service = reviews.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            return reviewResult(service.claim(caseId, actor, correlationId, idempotencyKey,
                    "ops:" + UUID.randomUUID()), correlationId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @PostMapping("/review-cases/{caseId}/resolve")
    ResponseEntity<?> resolveReviewCase(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Approver", required = false) String approver,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ResolveReviewRequest request) {
        if (request == null) return problem(HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", false, correlationId);
        ReviewCaseService service = reviews.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            return reviewResult(service.resolve(caseId, actor, approver, correlationId,
                    idempotencyKey, request.resolutionType(), request.resolutionNote(),
                    request.causeRef(), "ops:" + UUID.randomUUID()), correlationId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @PostMapping("/review-cases/{caseId}/corrections")
    ResponseEntity<?> correctReviewCase(
            @PathVariable String caseId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Approver", required = false) String approver,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ManualCorrectionRequest request) {
        if (request == null) return problem(HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", false, correlationId);
        ManualCorrectionService service = corrections.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            var command = new ManualCorrectionService.Command(caseId, request.aggregateType(),
                    request.aggregateId(), request.trigger(), request.amountMinor(), request.currency(),
                    request.providerStatus(), request.providerObjectId(),
                    request.providerBalanceTransactionId(), request.providerResponseJson(),
                    actor, approver, correlationId, idempotencyKey, request.errorCode(), request.reasonCode());
            var result = service.correct(command, "ops:" + UUID.randomUUID());
            return switch (result.outcome()) {
                case APPLIED -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Correlation-Id", correlationId).build();
                case MISSING -> problem(HttpStatus.NOT_FOUND, "REVIEW_CASE_NOT_FOUND", false, correlationId);
                case INVALID_TRANSITION, KEY_REUSED -> problem(HttpStatus.UNPROCESSABLE_ENTITY,
                        result.outcome() == ManualCorrectionService.Outcome.INVALID_TRANSITION
                                ? "INVALID_TRANSITION" : "IDEMPOTENCY_KEY_REUSED", false, correlationId);
                case CONFLICT, IN_PROGRESS, FENCED -> problem(HttpStatus.CONFLICT,
                        "REVIEW_CASE_CONFLICT", true, correlationId);
            };
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @PostMapping("/webhooks/{stripeEventId}/replay")
    ResponseEntity<?> replay(
            @PathVariable String stripeEventId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Approver", required = false) String approver,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ReplayRequest request) {
        final WebhookReplayCommand command;
        try {
            if (request == null) throw new IllegalArgumentException("body is required");
            command = new WebhookReplayCommand(stripeEventId, actor, approver,
                    correlationId, idempotencyKey, request.causeRef());
        } catch (RuntimeException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        WebhookReplayService service = replays.getIfAvailable();
        if (service == null)
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        WebhookReplayResult result = service.replay(command, "ops:" + UUID.randomUUID());
        return switch (result.outcome()) {
            case SCHEDULED -> ResponseEntity.accepted()
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Correlation-Id", correlationId).build();
            case MISSING -> problem(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", false, correlationId);
            case PAYLOAD_SHREDDED -> problem(HttpStatus.GONE, "WEBHOOK_PAYLOAD_SHREDDED", false, correlationId);
            case BUSY, REQUEST_IN_PROGRESS, FENCED ->
                    problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", true, correlationId);
            case IDEMPOTENCY_KEY_REUSED ->
                    problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", false, correlationId);
        };
    }

    @PostMapping("/reconciliation-runs")
    ResponseEntity<?> startReconciliation(
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Approver", required = false) String approver,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ReconciliationRequest request) {
        if (request == null) return problem(HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", false, correlationId);
        ReconciliationOperationsService service = reconciliation.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            var result = service.start(new ReconciliationOperationsService.StartCommand(
                    request.lane(), request.windowFrom(), request.windowTo(), actor, approver,
                    correlationId, idempotencyKey), "ops:" + UUID.randomUUID());
            return operationResult(result, correlationId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @PutMapping("/reconciliation/freeze")
    ResponseEntity<?> setReconciliationFreeze(
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Approver", required = false) String approver,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) FreezeRequest request) {
        if (request == null) return problem(HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", false, correlationId);
        ReconciliationOperationsService service = reconciliation.getIfAvailable();
        if (service == null) return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", true, correlationId);
        try {
            var result = service.freeze(new ReconciliationOperationsService.FreezeCommand(
                    requireFrozen(request.frozen()), request.causeRef(), actor, approver,
                    correlationId, idempotencyKey), "ops:" + UUID.randomUUID());
            return operationResult(result, correlationId);
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
    }

    @GetMapping("/ledger/journals/{journalId}")
    ResponseEntity<?> getJournal(
            @PathVariable String journalId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        if (!validReadHeaders(actor, correlationId))
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        return financialQueries.findJournal(journalId)
                .<ResponseEntity<?>>map(value -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Correlation-Id", correlationId)
                        .body(JournalResponse.from(value)))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND,
                        "FINANCIAL_JOURNAL_NOT_FOUND", false, correlationId));
    }

    @GetMapping("/disputes/{disputeTransactionId}")
    ResponseEntity<?> getDispute(
            @PathVariable String disputeTransactionId,
            @RequestHeader(name = "X-Actor", required = false) String actor,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        if (!validReadHeaders(actor, correlationId))
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        return financialQueries.findDispute(disputeTransactionId)
                .<ResponseEntity<?>>map(value -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Correlation-Id", correlationId)
                        .body(DisputeResponse.from(value)))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND,
                        "DISPUTE_NOT_FOUND", false, correlationId));
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, boolean retryable, String correlationId) {
        String safe = correlationId == null || correlationId.isBlank() || correlationId.length() > 128
                ? "missing-correlation-id" : correlationId;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", safe).body(new Problem(code, retryable, safe));
    }

    private static ResponseEntity<?> reviewResult(ReviewCaseService.Result result,
                                                  String correlationId) {
        return switch (result.outcome()) {
            case OK -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Correlation-Id", correlationId)
                    .body(ReviewCaseResponse.from(result.reviewCase()));
            case MISSING -> problem(HttpStatus.NOT_FOUND, "REVIEW_CASE_NOT_FOUND", false, correlationId);
            case INVALID, KEY_REUSED -> problem(HttpStatus.UNPROCESSABLE_ENTITY,
                    result.outcome() == ReviewCaseService.Outcome.INVALID
                            ? "CORRECTION_EVIDENCE_MISSING" : "IDEMPOTENCY_KEY_REUSED",
                    false, correlationId);
            case CONFLICT, IN_PROGRESS, FENCED -> problem(HttpStatus.CONFLICT,
                    "REVIEW_CASE_CONFLICT", true, correlationId);
        };
    }

    private static ResponseEntity<?> operationResult(ReconciliationOperationsService.Result result,
                                                      String correlationId) {
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Correlation-Id", correlationId).body(result.body());
            case COMPLETED -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Correlation-Id", correlationId).body(result.body());
            case ALREADY_RUNNING, IN_PROGRESS, FENCED -> problem(HttpStatus.CONFLICT,
                    "RECONCILIATION_ALREADY_RUNNING", true, correlationId);
            case KEY_REUSED -> problem(HttpStatus.UNPROCESSABLE_ENTITY,
                    "IDEMPOTENCY_KEY_REUSED", false, correlationId);
            case FAILED -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                    "RECONCILIATION_FAILED", true, correlationId);
        };
    }

    private static boolean requireFrozen(Boolean frozen) {
        if (frozen == null) throw new IllegalArgumentException("frozen is required");
        return frozen;
    }

    private static boolean validReadHeaders(String actor, String correlationId) {
        return actor != null && !actor.isBlank() && actor.length() <= 128
                && correlationId != null && !correlationId.isBlank()
                && correlationId.length() <= 128;
    }

    record ReplayRequest(@JsonProperty("cause_ref") String causeRef) {}
    record ResolveReviewRequest(
            @JsonProperty("resolution_type") String resolutionType,
            @JsonProperty("resolution_note") String resolutionNote,
            @JsonProperty("cause_ref") String causeRef) {}
    record ManualCorrectionRequest(
            @JsonProperty("aggregate_type") String aggregateType,
            @JsonProperty("aggregate_id") String aggregateId,
            String trigger,
            @JsonProperty("amount_minor") long amountMinor,
            String currency,
            @JsonProperty("provider_status") String providerStatus,
            @JsonProperty("provider_object_id") String providerObjectId,
            @JsonProperty("provider_balance_transaction_id") String providerBalanceTransactionId,
            @JsonProperty("provider_response_json") String providerResponseJson,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("reason_code") String reasonCode) {}
    record ReconciliationRequest(
            String lane,
            @JsonProperty("window_from") Instant windowFrom,
            @JsonProperty("window_to") Instant windowTo) {}
    record FreezeRequest(Boolean frozen, @JsonProperty("cause_ref") String causeRef) {}
    record Money(@JsonProperty("amount_minor") long amountMinor, String currency) {}
    record JournalLine(@JsonProperty("line_no") short lineNo,
                       @JsonProperty("account_code") String accountCode,
                       String direction, Money amount) {
        static JournalLine from(FinancialOperationsQueryPort.Line value) {
            return new JournalLine(value.lineNo(), value.accountCode(), value.direction(),
                    new Money(value.amountMinor(), value.currency()));
        }
    }
    record JournalResponse(@JsonProperty("journal_id") String journalId,
                           @JsonProperty("journal_type") String journalType,
                           Money amount, @JsonProperty("occurred_at") Instant occurredAt,
                           List<JournalLine> lines) {
        static JournalResponse from(FinancialOperationsQueryPort.Journal value) {
            return new JournalResponse(value.id(), value.journalType(),
                    new Money(value.amountMinor(), value.currency()), value.occurredAt(),
                    value.lines().stream().map(JournalLine::from).toList());
        }
    }
    record DisputeResponse(@JsonProperty("dispute_transaction_id") String disputeTransactionId,
                           @JsonProperty("payment_transaction_id") String paymentTransactionId,
                           @JsonProperty("order_id") String orderId,
                           @JsonProperty("provider_dispute_id") String providerDisputeId,
                           Money amount, @JsonProperty("reason_code") String reasonCode,
                           String status, @JsonProperty("funds_state") String fundsState,
                           @JsonProperty("evidence_due_at") Instant evidenceDueAt,
                           @JsonProperty("opened_at") Instant openedAt,
                           @JsonProperty("closed_at") Instant closedAt) {
        static DisputeResponse from(FinancialOperationsQueryPort.Dispute value) {
            return new DisputeResponse(value.id(), value.paymentId(), Long.toString(value.orderId()),
                    value.providerDisputeId(), new Money(value.amountMinor(), value.currency()),
                    value.reasonCode(), value.status(), value.fundsState(), value.evidenceDueAt(),
                    value.openedAt(), value.closedAt());
        }
    }
    record ReviewCaseResponse(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("payment_transaction_id") String paymentTransactionId,
            @JsonProperty("refund_transaction_id") String refundTransactionId,
            @JsonProperty("reason_code") String reasonCode,
            String status,
            @JsonProperty("assigned_to") String assignedTo,
            @JsonProperty("opened_at") Instant openedAt,
            @JsonProperty("resolution_type") String resolutionType,
            @JsonProperty("resolved_by") String resolvedBy,
            @JsonProperty("resolved_at") Instant resolvedAt) {
        static ReviewCaseResponse from(ReviewCasePort.Case value) {
            return new ReviewCaseResponse(value.id(), value.paymentId(), value.refundId(),
                    value.reasonCode(), value.status(), value.assignedTo(), value.openedAt(),
                    value.resolutionType(), value.resolvedBy(), value.resolvedAt());
        }
    }
    record Problem(String code, boolean retryable,
                   @JsonProperty("correlation_id") String correlationId) {}
}

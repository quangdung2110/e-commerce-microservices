package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.paymentservice.application.port.out.PaymentQueryPort;
import com.platform.paymentservice.application.service.PaymentQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** {@code getPayment} and {@code getRefund} of contracts/http/payment/v1 (Order workload, read-only). */
@RestController
final class PaymentQueryController {
    private static final Pattern TRANSACTION_ID = Pattern.compile("^TXN_[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern REFUND_ID = Pattern.compile("^RFD_[0-9A-HJKMNP-TV-Z]{26}$");
    private final ObjectProvider<PaymentQueryService> queries;

    PaymentQueryController(ObjectProvider<PaymentQueryService> queries) {
        this.queries = queries;
    }

    @GetMapping("/v1/payments/{transactionId}")
    ResponseEntity<?> payment(
            @PathVariable String transactionId,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        if (!validCommonHeaders(serviceIdentity, correlationId) || !TRANSACTION_ID.matcher(transactionId).matches()) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        PaymentQueryService service = queries.getIfAvailable();
        if (service == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        }
        return service.payment(transactionId)
                .<ResponseEntity<?>>map(view -> ok(PaymentBody.of(view), correlationId))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", false, correlationId));
    }

    @GetMapping("/v1/refunds/{refundTransactionId}")
    ResponseEntity<?> refund(
            @PathVariable String refundTransactionId,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        if (!validCommonHeaders(serviceIdentity, correlationId) || !REFUND_ID.matcher(refundTransactionId).matches()) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        PaymentQueryService service = queries.getIfAvailable();
        if (service == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        }
        return service.refund(refundTransactionId)
                .<ResponseEntity<?>>map(view -> ok(RefundBody.of(view), correlationId))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", false, correlationId));
    }

    private static ResponseEntity<Object> ok(Object body, String correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", correlationId)
                .body(body);
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, boolean retryable, String correlationId) {
        String safe = safeCorrelation(correlationId);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", safe)
                .headers(headers -> RetryAfterHeader.apply(headers, status.value(), retryable))
                .body(new Problem(code, retryable, safe));
    }

    private static boolean validCommonHeaders(String identity, String correlationId) {
        // The caller allowlist is enforced by ServiceIdentityFilter (contracts ADR-004); only presence is left here.
        return identity != null && !identity.isBlank()
                && correlationId != null && !correlationId.isBlank() && correlationId.length() <= 128;
    }

    private static String safeCorrelation(String correlationId) {
        return correlationId == null || correlationId.isBlank() || correlationId.length() > 128
                ? "missing-correlation-id" : correlationId;
    }

    record Money(@JsonProperty("amount_minor") long amountMinor, String currency) {}

    record RefundBody(
            @JsonProperty("refund_transaction_id") String refundTransactionId,
            @JsonProperty("payment_transaction_id") String paymentTransactionId,
            @JsonProperty("order_id") String orderId,
            Money amount, String status) {
        static RefundBody of(PaymentQueryPort.RefundView view) {
            return new RefundBody(view.refundTransactionId(), view.paymentTransactionId(),
                    Long.toString(view.orderId()), new Money(view.amountMinor(), view.currency()), view.status());
        }
    }

    record DisputeBody(
            @JsonProperty("dispute_transaction_id") String disputeTransactionId,
            @JsonProperty("payment_transaction_id") String paymentTransactionId,
            @JsonProperty("order_id") String orderId,
            Money amount, String status,
            @JsonProperty("funds_state") String fundsState,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("evidence_due_at") Instant evidenceDueAt,
            @JsonProperty("opened_at") Instant openedAt,
            @JsonProperty("closed_at") Instant closedAt) {
        static DisputeBody of(PaymentQueryPort.DisputeView view) {
            return new DisputeBody(view.disputeTransactionId(), view.paymentTransactionId(),
                    Long.toString(view.orderId()), new Money(view.amountMinor(), view.currency()), view.status(),
                    view.fundsState(), view.reasonCode(), view.evidenceDueAt(), view.openedAt(), view.closedAt());
        }
    }

    record PaymentBody(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("payment_attempt_number") int paymentAttemptNumber,
            Money amount, String status,
            @JsonProperty("financial_state_version") long financialStateVersion,
            @JsonProperty("requires_manual_review") boolean requiresManualReview,
            @JsonProperty("succeeded_at") Instant succeededAt,
            @JsonProperty("terminal_at") Instant terminalAt,
            RefundBody refund, List<DisputeBody> disputes) {
        static PaymentBody of(PaymentQueryPort.PaymentView view) {
            return new PaymentBody(view.transactionId(), Long.toString(view.orderId()), view.paymentAttemptNumber(),
                    new Money(view.amountMinor(), view.currency()), view.status(), view.financialStateVersion(),
                    view.requiresManualReview(), view.succeededAt(), view.terminalAt(),
                    view.refund() == null ? null : RefundBody.of(view.refund()),
                    view.disputes().stream().map(DisputeBody::of).toList());
        }
    }

    record Problem(String code, boolean retryable, @JsonProperty("correlation_id") String correlationId) {}
}

package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.platform.paymentservice.application.service.InitiatePaymentCommand;
import com.platform.paymentservice.application.service.CancelPaymentCommand;
import com.platform.paymentservice.application.service.CancelPaymentResult;
import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.InitiatePaymentResult;
import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.application.service.PaymentClientDataResult;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import com.platform.paymentservice.application.service.RefundPaymentCommand;
import com.platform.paymentservice.application.service.RefundPaymentResult;
import com.platform.paymentservice.application.service.RefundPaymentService;
import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.domain.PaymentStatus;
import com.platform.paymentservice.domain.RefundEvidence;
import com.platform.paymentservice.domain.RefundReason;
import com.platform.paymentservice.domain.ReleasedBeforeDispatchEvidence;
import com.platform.paymentservice.domain.ReturnScopeInspectionEvidence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/payments")
final class PaymentController {
    private static final Pattern TRANSACTION_ID =
            Pattern.compile("^TXN_[0-9A-HJKMNP-TV-Z]{26}$");
    private final ObjectProvider<InitiatePaymentService> initiate;
    private final ObjectProvider<PaymentClientDataService> clientData;
    private final ObjectProvider<CancelPaymentService> cancel;
    private final ObjectProvider<RefundPaymentService> refund;

    PaymentController(ObjectProvider<InitiatePaymentService> initiate,
                      ObjectProvider<PaymentClientDataService> clientData,
                      ObjectProvider<CancelPaymentService> cancel,
                      ObjectProvider<RefundPaymentService> refund) {
        this.initiate = initiate;
        this.clientData = clientData;
        this.cancel = cancel;
        this.refund = refund;
    }

    @PostMapping("/{transactionId}/refund")
    ResponseEntity<?> refund(
            @PathVariable String transactionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) RefundRequest request) {
        if ((idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() < 16 || idempotencyKey.length() > 255))
            return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING", false, correlationId);
        if (!validCommonHeaders(serviceIdentity, correlationId) || request == null
                || !TRANSACTION_ID.matcher(transactionId).matches())
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, safeCorrelation(correlationId));
        final RefundPaymentCommand command;
        try {
            command = request.command(transactionId, idempotencyKey, correlationId);
        } catch (RuntimeException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        RefundPaymentService service = refund.getIfAvailable();
        if (service == null)
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        RefundPaymentResult result = service.refund(command, "api:" + UUID.randomUUID());
        if (result instanceof RefundPaymentResult.Processing value)
            return ok(new RefundResponse(value.refundTransactionId(),
                    value.paymentTransactionId(), "PROCESSING"), correlationId);
        if (result instanceof RefundPaymentResult.PaymentNotFound)
            return problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", false, correlationId);
        if (result instanceof RefundPaymentResult.OrderMismatch)
            return problem(HttpStatus.BAD_REQUEST, "ORDER_MISMATCH", false, correlationId);
        if (result instanceof RefundPaymentResult.PaymentNotSuccess)
            return problem(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_SUCCESS", false, correlationId);
        if (result instanceof RefundPaymentResult.AmountExceeded)
            return problem(HttpStatus.BAD_REQUEST, "REFUND_AMOUNT_EXCEEDED", false, correlationId);
        if (result instanceof RefundPaymentResult.OrderRefundAlreadyExists)
            return problem(HttpStatus.CONFLICT, "ORDER_REFUND_ALREADY_EXISTS", false, correlationId);
        if (result instanceof RefundPaymentResult.IdempotencyKeyReused)
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", false, correlationId);
        if (result instanceof RefundPaymentResult.RequestInProgress || result instanceof RefundPaymentResult.Fenced)
            return problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", true, correlationId);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_OUTCOME_UNKNOWN", true, correlationId);
    }

    @PostMapping("/{transactionId}/cancel")
    ResponseEntity<?> cancel(
            @PathVariable String transactionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) CancelRequest request) {
        if ((idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() < 16 || idempotencyKey.length() > 255))
            return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING", false, correlationId);
        if (!validCommonHeaders(serviceIdentity, correlationId) || request == null
                || !TRANSACTION_ID.matcher(transactionId).matches()) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, safeCorrelation(correlationId));
        }
        final CancelPaymentCommand command;
        try {
            command = new CancelPaymentCommand(transactionId, request.orderIdAsLong(),
                    CancelPaymentCommand.Reason.valueOf(request.reason()), idempotencyKey, correlationId);
        } catch (RuntimeException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        CancelPaymentService service = cancel.getIfAvailable();
        if (service == null)
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        CancelPaymentResult result = service.cancel(command, "api:" + UUID.randomUUID());
        if (result instanceof CancelPaymentResult.Canceled value)
            return ok(new CancelResponse(value.transactionId(), Long.toString(value.orderId()),
                    "CANCELED", value.financialStateVersion(), null), correlationId);
        if (result instanceof CancelPaymentResult.AlreadyNonCaptured value)
            return ok(new CancelResponse(value.transactionId(), Long.toString(value.orderId()),
                    "ALREADY_NON_CAPTURED", value.financialStateVersion(), null), correlationId);
        if (result instanceof CancelPaymentResult.CancelPending value)
            return ok(new CancelResponse(value.transactionId(), Long.toString(value.orderId()),
                    "CANCEL_PENDING", value.financialStateVersion(), null), correlationId);
        if (result instanceof CancelPaymentResult.AlreadySucceeded value)
            return ok(new CancelResponse(value.transactionId(), Long.toString(value.orderId()),
                    "ALREADY_SUCCEEDED", value.financialStateVersion(),
                    new Amount(value.amount().amountMinor(), value.amount().currency())), correlationId);
        if (result instanceof CancelPaymentResult.PaymentNotFound)
            return problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", false, correlationId);
        if (result instanceof CancelPaymentResult.OrderMismatch)
            return problem(HttpStatus.BAD_REQUEST, "ORDER_MISMATCH", false, correlationId);
        if (result instanceof CancelPaymentResult.IdempotencyKeyReused)
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", false, correlationId);
        if (result instanceof CancelPaymentResult.RequestInProgress
                || result instanceof CancelPaymentResult.Fenced)
            return problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", true, correlationId);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_OUTCOME_UNKNOWN", true, correlationId);
    }

    @PostMapping("/initiate")
    ResponseEntity<?> initiate(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) InitiateRequest request) {
        if ((idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() < 16 || idempotencyKey.length() > 255)) {
            return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING", false, correlationId);
        }
        if (!validCommonHeaders(serviceIdentity, correlationId) || request == null) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, safeCorrelation(correlationId));
        }
        final InitiatePaymentCommand command;
        try {
            request.validate();
            command = new InitiatePaymentCommand(
                    idempotencyKey, Long.parseLong(request.orderId()),
                    request.paymentAttemptNumber(),
                    new Money(request.amount().amountMinor(), request.amount().currency()),
                    UUID.fromString(request.userId()), request.returnUrl(), correlationId);
        } catch (RuntimeException invalid) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }

        InitiatePaymentService service = initiate.getIfAvailable();
        if (service == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        }
        InitiatePaymentResult result = service.initiate(command, "api:" + UUID.randomUUID());
        if (result instanceof InitiatePaymentResult.Pending pending) {
            return ok(new PendingResponse(
                    pending.transactionId(), pending.paymentAttemptNumber(), "PENDING",
                    pending.clientSecret(), pending.publishableKey(), pending.expiresAt()), correlationId);
        }
        if (result instanceof InitiatePaymentResult.WithoutClientData without) {
            return ok(new WithoutClientDataResponse(
                    without.transactionId(), without.paymentAttemptNumber(), without.status()), correlationId);
        }
        if (result instanceof InitiatePaymentResult.RequestInProgress
                || result instanceof InitiatePaymentResult.Fenced) {
            return problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", true, correlationId);
        }
        if (result instanceof InitiatePaymentResult.IdempotencyKeyReused) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", false, correlationId);
        }
        if (result instanceof InitiatePaymentResult.AttemptAlreadyExists) {
            return problem(HttpStatus.CONFLICT, "ATTEMPT_ALREADY_EXISTS", false, correlationId);
        }
        if (result instanceof InitiatePaymentResult.InvalidAttemptSequence) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, correlationId);
        }
        if (result instanceof InitiatePaymentResult.ProviderOutcomeUnknown
                || result instanceof InitiatePaymentResult.ReviewRequired) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_OUTCOME_UNKNOWN", true, correlationId);
        }
        if (result instanceof InitiatePaymentResult.ProviderRejected) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_REQUEST_REJECTED", false, correlationId);
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
    }

    @GetMapping("/{transactionId}/client-data")
    ResponseEntity<?> clientData(
            @PathVariable String transactionId,
            @RequestHeader(name = "X-Service-Identity", required = false) String serviceIdentity,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        if (!validCommonHeaders(serviceIdentity, correlationId)
                || !TRANSACTION_ID.matcher(transactionId).matches()) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", false, safeCorrelation(correlationId));
        }
        PaymentClientDataService service = clientData.getIfAvailable();
        if (service == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", true, correlationId);
        }
        PaymentClientDataResult result = service.get(transactionId);
        if (result instanceof PaymentClientDataResult.NotFound) {
            return problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", false, correlationId);
        }
        if (result instanceof PaymentClientDataResult.Usable usable) {
            return ok(new ClientDataResponse(
                    usable.transactionId(), usable.paymentAttemptNumber(), usable.status(), true,
                    usable.clientSecret(), usable.publishableKey(), usable.clientExpiresAt()), correlationId);
        }
        PaymentClientDataResult.Unusable unusable = (PaymentClientDataResult.Unusable) result;
        return ok(new ClientDataResponse(
                unusable.transactionId(), unusable.paymentAttemptNumber(), unusable.status(), false,
                null, null, unusable.clientExpiresAt()), correlationId);
    }

    private static ResponseEntity<Object> ok(Object body, String correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Correlation-Id", correlationId)
                .body(body);
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, boolean retryable, String correlationId) {
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
                && correlationId != null && !correlationId.isBlank()
                && correlationId.length() <= 128;
    }

    private static String safeCorrelation(String correlationId) {
        return correlationId == null || correlationId.isBlank() || correlationId.length() > 128
                ? "missing-correlation-id" : correlationId;
    }

    record InitiateRequest(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("payment_attempt_number") int paymentAttemptNumber,
            Amount amount,
            @JsonProperty("user_id") String userId,
            @JsonProperty("return_url") String returnUrl) {
        void validate() {
            if (orderId == null || !orderId.matches("^[1-9][0-9]*$") || orderId.length() > 19
                    || paymentAttemptNumber < 1 || amount == null || userId == null) {
                throw new IllegalArgumentException("invalid initiate body");
            }
            if (returnUrl != null) {
                if (returnUrl.length() > 2048) throw new IllegalArgumentException("return_url is too long");
                try {
                    URI uri = new URI(returnUrl);
                    if (!uri.isAbsolute()) throw new IllegalArgumentException("return_url must be absolute");
                }
                catch (URISyntaxException invalid) { throw new IllegalArgumentException(invalid); }
            }
        }
    }

    record Amount(@JsonProperty("amount_minor") long amountMinor, String currency) {}

    record CancelRequest(@JsonProperty("order_id") String orderId, String reason) {
        long orderIdAsLong() {
            if (orderId == null || !orderId.matches("^[1-9][0-9]*$") || orderId.length() > 19
                    || reason == null) throw new IllegalArgumentException("invalid cancel body");
            return Long.parseLong(orderId);
        }
    }

    record RefundRequest(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("refund_request_id") String refundRequestId,
            Amount amount,
            String reason,
            @JsonProperty("inventory_evidence") InventoryEvidence evidence) {
        RefundPaymentCommand command(String transactionId, String idempotencyKey, String correlationId) {
            if (orderId == null || !orderId.matches("^[1-9][0-9]*$") || orderId.length() > 19
                    || refundRequestId == null || amount == null || reason == null || evidence == null)
                throw new IllegalArgumentException("invalid refund body");
            RefundReason parsedReason = RefundReason.valueOf(reason);
            RefundEvidence parsedEvidence = evidence.toDomain();
            return new RefundPaymentCommand(transactionId, Long.parseLong(orderId), refundRequestId,
                    new Money(amount.amountMinor(), amount.currency()), parsedReason, parsedEvidence,
                    idempotencyKey, correlationId);
        }
    }

    record InventoryEvidence(
            String type,
            @JsonProperty("refund_scope_id") String refundScopeId,
            Integer expected, Integer received, Integer passed, Integer failed,
            @JsonProperty("release_reference") String releaseReference) {
        RefundEvidence toDomain() {
            if ("RETURN_SCOPE_INSPECTION_COMPLETED".equals(type)
                    && expected != null && received != null && passed != null && failed != null
                    && releaseReference == null)
                return new ReturnScopeInspectionEvidence(refundScopeId, expected, received, passed, failed);
            if ("COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH".equals(type)
                    && refundScopeId == null && expected == null && received == null
                    && passed == null && failed == null)
                return new ReleasedBeforeDispatchEvidence(releaseReference);
            throw new IllegalArgumentException("invalid inventory evidence");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CancelResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("order_id") String orderId,
            String outcome,
            @JsonProperty("financial_state_version") long financialStateVersion,
            Amount amount) {}

    record RefundResponse(
            @JsonProperty("refund_transaction_id") String refundTransactionId,
            @JsonProperty("payment_transaction_id") String paymentTransactionId,
            String status) {}

    record PendingResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("payment_attempt_number") int paymentAttemptNumber,
            String status,
            @JsonProperty("client_secret") char[] clientSecret,
            @JsonProperty("publishable_key") String publishableKey,
            @JsonProperty("expires_at") Instant expiresAt) {}

    record WithoutClientDataResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("payment_attempt_number") int paymentAttemptNumber,
            PaymentStatus status) {}

    record ClientDataResponse(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("payment_attempt_number") int paymentAttemptNumber,
            PaymentStatus status,
            boolean usable,
            @JsonProperty("client_secret") char[] clientSecret,
            @JsonProperty("publishable_key") String publishableKey,
            @JsonProperty("client_expires_at") Instant clientExpiresAt) {}

    record Problem(String code, boolean retryable,
                   @JsonProperty("correlation_id") String correlationId) {}
}

package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.CheckoutRules;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.SubmissionStore;
import com.ecommerce.order.store.SubmissionStore.SubmissionRow;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST/GET /internal/v1/checkout-order-submissions} (WF-ORDER-004): persists the
 * submission and the workflow dispatch in one transaction, then waits only until the
 * PAYMENT_READY checkpoint (synchronous-to-checkpoint). Replay is driven by the unique
 * checkout token and the stored canonical digest, never by Redis.
 */
@Service
public class CheckoutSubmissionService {

    private final SubmissionStore submissions;
    private final OrderStore orders;
    private final WorkflowEnqueuer enqueuer;
    private final PaymentClientDataReader clientData;
    private final TransactionTemplate tx;
    private final Json json;
    private final OrderProperties.Checkout config;

    public CheckoutSubmissionService(SubmissionStore submissions, OrderStore orders, WorkflowEnqueuer enqueuer,
                                     PaymentClientDataReader clientData, TransactionTemplate tx, Json json,
                                     OrderProperties properties) {
        this.submissions = submissions;
        this.orders = orders;
        this.enqueuer = enqueuer;
        this.clientData = clientData;
        this.tx = tx;
        this.json = json;
        this.config = properties.checkout();
    }

    public ResponseEntity<Object> submit(String idempotencyKey, CheckoutOrderSubmissionRequest request) {
        if (!request.checkoutToken().equalsIgnoreCase(idempotencyKey)) {
            throw ApiException.invalid("Idempotency-Key must equal checkout_token");
        }
        List<ApiException.FieldError> errors = new ArrayList<>(CheckoutRules.validate(request));
        if (request.pricing().finalAmount().amountMinor() < 1) {
            errors.add(new ApiException.FieldError("pricing.final_amount", "must be at least 1 VND for Payment"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_CHECKOUT_SNAPSHOT, "Checkout snapshot is inconsistent", errors,
                    null, Map.of());
        }
        UUID token = UUID.fromString(request.checkoutToken());
        JsonNode tree = json.tree(request);
        String digest = json.digest(tree);
        Boolean created = tx.execute(s -> {
            boolean inserted = submissions.insertIfAbsent(token, UUID.fromString(request.userId()), digest,
                    json.write(tree));
            if (!inserted) {
                SubmissionRow existing = submissions.find(token).orElseThrow();
                if (!existing.requestDigest().equals(digest)) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "checkout_token was already submitted with a different snapshot");
                }
                return false;
            }
            enqueuer.dispatch(null, enqueuer.checkout(token.toString()));
            return true;
        });
        SubmissionRow submission = awaitCheckpoint(token);
        return switch (submission.state()) {
            case "PAYMENT_READY" -> ready(submission, Boolean.TRUE.equals(created) ? HttpStatus.CREATED : HttpStatus.OK);
            case "PREPARING" -> throw new ApiException(ErrorCode.SUBMISSION_IN_PROGRESS,
                    "Order is still preparing payment; retry with the identical key and body", List.of(), 2, Map.of());
            default -> failure(submission);
        };
    }

    public ObjectNode status(String checkoutToken) {
        UUID token = Ids.parseUuid(checkoutToken);
        SubmissionRow submission = token == null ? null : submissions.find(token).orElse(null);
        if (submission == null) {
            throw ApiException.notFound();
        }
        ObjectNode body = json.object();
        body.put("checkout_token", submission.checkoutToken().toString());
        body.put("state", submission.state());
        body.put("order_id", submission.orderId() == null ? null : Ids.wire(submission.orderId()));
        if ("PAYMENT_READY".equals(submission.state())) {
            body.set("payment", payment(submission));
        } else {
            body.putNull("payment");
        }
        body.set("failure", submission.failureJson() == null ? json.mapper().nullNode() : json.read(submission.failureJson()));
        body.put("updated_at", submission.updatedAt().toString());
        return body;
    }

    private SubmissionRow awaitCheckpoint(UUID token) {
        long deadline = System.nanoTime() + config.submitWait().toNanos();
        while (true) {
            SubmissionRow submission = submissions.find(token).orElseThrow();
            if (!"PREPARING".equals(submission.state()) || System.nanoTime() >= deadline) {
                return submission;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return submission;
            }
        }
    }

    private ResponseEntity<Object> ready(SubmissionRow submission, HttpStatus status) {
        ObjectNode body = json.object();
        body.put("checkout_token", submission.checkoutToken().toString());
        body.put("order_id", Ids.wire(submission.orderId()));
        body.put("order_status", "PENDING_PAYMENT");
        body.set("payment", payment(submission));
        return ResponseEntity.status(status)
                .header(HttpHeaders.LOCATION, "/internal/v1/checkout-order-submissions/" + submission.checkoutToken())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private ObjectNode payment(SubmissionRow submission) {
        OrderStore.OrderRow order = orders.find(submission.orderId()).orElseThrow();
        // The initial attempt is the payment ready at the checkpoint.
        return clientData.read(order.activeAttemptNumber(), order.activeTransactionId(),
                WorkflowNames.checkout(order.checkoutToken()));
    }

    private ResponseEntity<Object> failure(SubmissionRow submission) {
        JsonNode problem = json.read(submission.failureJson());
        return ResponseEntity.status(problem.path("status").asInt(409))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}

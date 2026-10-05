package com.ecommerce.order.web;

import com.ecommerce.order.service.CheckoutSubmissionService;
import com.ecommerce.order.service.ReconciliationDecisionService;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.web.dto.ReconciliationDtos.DecisionRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Workload-identity surface of contracts/http/order/internal/v1. */
@RestController
public class InternalController {

    private final CheckoutSubmissionService submissions;
    private final ReconciliationDecisionService reconciliation;

    public InternalController(CheckoutSubmissionService submissions, ReconciliationDecisionService reconciliation) {
        this.submissions = submissions;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/internal/v1/checkout-order-submissions")
    public ResponseEntity<Object> submit(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CheckoutOrderSubmissionRequest request) {
        return submissions.submit(key, request);
    }

    @GetMapping("/internal/v1/checkout-order-submissions/{checkoutToken}")
    public ResponseEntity<ObjectNode> submission(@PathVariable String checkoutToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(submissions.status(checkoutToken));
    }

    @GetMapping("/internal/v1/orders/{orderId}/financial-reconciliation-case")
    public ResponseEntity<ObjectNode> reconciliationCase(@PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(reconciliation.read(orderId));
    }

    @PostMapping("/internal/v1/orders/{orderId}/financial-reconciliation-decisions")
    public ResponseEntity<Object> decide(@PathVariable String orderId, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody DecisionRequest request) {
        return reconciliation.decide(orderId, key, request, Caller.current());
    }
}

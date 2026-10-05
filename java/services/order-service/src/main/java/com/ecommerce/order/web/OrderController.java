package com.ecommerce.order.web;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.BuyerTab;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.PaymentStatus;
import com.ecommerce.order.service.OrderCommandService;
import com.ecommerce.order.service.OrderQueryService;
import com.ecommerce.order.service.OrderSearchService;
import com.ecommerce.order.web.dto.CommandDtos;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Buyer and Admin surface of contracts/http/order/v1 (Gateway identity in {@link IdentityFilter}). */
@RestController
@Validated
public class OrderController {

    private final OrderQueryService queries;
    private final OrderSearchService search;
    private final OrderCommandService commands;
    private final Json json;

    public OrderController(OrderQueryService queries, OrderSearchService search, OrderCommandService commands,
                           Json json) {
        this.queries = queries;
        this.search = search;
        this.commands = commands;
        this.json = json;
    }

    // ------------------------------------------------------------------ Buyer reads

    @GetMapping("/api/v1/orders")
    public ResponseEntity<ObjectNode> listMine(
            @RequestParam(required = false) BuyerTab tab,
            @RequestParam(required = false) @Size(min = 1, max = 200) String q,
            @RequestParam(name = "created_from", required = false) String createdFrom,
            @RequestParam(name = "created_to", required = false) String createdTo,
            @RequestParam(name = "order_status", required = false) @Size(max = 10) List<OrderStatus> orderStatus,
            @RequestParam(name = "payment_status", required = false) @Size(max = 6) List<PaymentStatus> paymentStatus,
            @RequestParam(name = "min_amount_minor", required = false) @Min(0) Long minAmount,
            @RequestParam(name = "max_amount_minor", required = false) @Min(0) Long maxAmount,
            @RequestParam(required = false) @Size(min = 1, max = 512) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Caller caller = Caller.current();
        return privateOk(search.search(new OrderSearchService.Criteria(caller.userId(), tab, q, instant(createdFrom),
                instant(createdTo), orderStatus, paymentStatus, minAmount, maxAmount, cursor, limit)));
    }

    @GetMapping("/api/v1/orders/{orderId}")
    public ResponseEntity<ObjectNode> getMine(@PathVariable String orderId) {
        return privateOk(queries.detail(orderId, Caller.current()));
    }

    @GetMapping("/api/v1/orders/{orderId}/refund-case")
    public ResponseEntity<ObjectNode> refundCase(@PathVariable String orderId) {
        return privateOk(queries.refundCase(orderId, Caller.current()));
    }

    @GetMapping("/api/v1/orders/{orderId}/payment-attempts/{attemptNumber}")
    public ResponseEntity<ObjectNode> paymentAttempt(@PathVariable String orderId,
                                                     @PathVariable @Min(1) int attemptNumber) {
        return privateOk(queries.paymentAttempt(orderId, attemptNumber, Caller.current()));
    }

    @GetMapping({"/api/v1/order-operations/{operationId}", "/api/v1/admin/order-operations/{operationId}"})
    public ResponseEntity<ObjectNode> operation(@PathVariable String operationId) {
        return privateOk(queries.operation(operationId, Caller.current()));
    }

    // ------------------------------------------------------------------ Buyer commands

    @PostMapping("/api/v1/orders/{orderId}/cancellation-requests")
    public ResponseEntity<Object> cancelMine(@PathVariable String orderId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody CommandDtos.BuyerCancellationRequest body) {
        return commands.cancel(orderId, Caller.current(), key, json.tree(body));
    }

    @PostMapping("/api/v1/orders/{orderId}/return-requests")
    public ResponseEntity<Object> requestReturn(@PathVariable String orderId,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CommandDtos.BuyerReturnRequest body) {
        return commands.requestReturn(orderId, Caller.current(), key, json.tree(body));
    }

    @PostMapping("/api/v1/orders/{orderId}/payment-attempts")
    public ResponseEntity<Object> createPaymentAttempt(@PathVariable String orderId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody CommandDtos.CreatePaymentAttemptRequest body) {
        return commands.createPaymentAttempt(orderId, Caller.current(), key, json.tree(body));
    }

    // ------------------------------------------------------------------ Admin

    @GetMapping("/api/v1/admin/orders")
    public ResponseEntity<ObjectNode> listAsAdmin(
            @RequestParam(name = "buyer_id", required = false) UUID buyerId,
            @RequestParam(required = false) BuyerTab tab,
            @RequestParam(required = false) @Size(min = 1, max = 200) String q,
            @RequestParam(name = "created_from", required = false) String createdFrom,
            @RequestParam(name = "created_to", required = false) String createdTo,
            @RequestParam(name = "order_status", required = false) @Size(max = 10) List<OrderStatus> orderStatus,
            @RequestParam(name = "payment_status", required = false) @Size(max = 6) List<PaymentStatus> paymentStatus,
            @RequestParam(name = "min_amount_minor", required = false) @Min(0) Long minAmount,
            @RequestParam(name = "max_amount_minor", required = false) @Min(0) Long maxAmount,
            @RequestParam(required = false) @Size(min = 1, max = 512) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return privateOk(search.search(new OrderSearchService.Criteria(buyerId, tab, q, instant(createdFrom),
                instant(createdTo), orderStatus, paymentStatus, minAmount, maxAmount, cursor, limit)));
    }

    @GetMapping("/api/v1/admin/orders/{orderId}")
    public ResponseEntity<ObjectNode> getAsAdmin(@PathVariable String orderId) {
        return privateOk(queries.detail(orderId, Caller.current()));
    }

    @PostMapping("/api/v1/admin/orders/{orderId}/cancellation-requests")
    public ResponseEntity<Object> cancelAsAdmin(@PathVariable String orderId,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CommandDtos.AdminCancellationRequest body) {
        return commands.cancel(orderId, Caller.current(), key, json.tree(body));
    }

    @PostMapping("/api/v1/admin/orders/{orderId}/pre-dispatch-refund-requests")
    public ResponseEntity<Object> preDispatchRefund(@PathVariable String orderId,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody CommandDtos.AdminPreDispatchRefundRequest body) {
        return commands.adminPreDispatchRefund(orderId, Caller.current(), key, json.tree(body));
    }

    private static ResponseEntity<ObjectNode> privateOk(ObjectNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException again) {
                throw ApiException.invalid("Invalid date-time: " + value);
            }
        }
    }
}

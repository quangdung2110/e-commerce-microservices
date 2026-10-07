package com.ecommerce.order.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Outbound ports; HTTP adapters implement them and tests substitute contract fakes. */
public final class Gateways {

    private Gateways() {
    }

    /** Headers every Inventory command needs (Idempotency-Key, correlation, causation). */
    public record Command(String commandId, String correlationId, String causationId) {
    }

    /** contracts/http/inventory/workflow/v1. */
    public interface Inventory {
        RemoteResult reserve(Command command, JsonNode body);

        RemoteResult commit(String orderId, Command command, JsonNode body);

        RemoteResult release(String orderId, Command command, JsonNode body);

        RemoteResult releaseCommittedBeforeDispatch(String orderId, Command command, JsonNode body);

        RemoteResult registerRefundScope(Command command, JsonNode body);

        RemoteResult closeRefundScope(String scopeId, Command command, JsonNode body);

        RemoteResult closeIncompleteRefundScope(String scopeId, Command command, JsonNode body);

        RemoteResult cancelRefundScope(String scopeId, Command command);

        RemoteResult getReservation(String orderId);

        RemoteResult getRefundScope(String scopeId);

        RemoteResult getCommand(String commandId);
    }

    /** contracts/http/promotion/v1 /internal/v1/promotion-allocation/*. */
    public interface Promotion {
        RemoteResult reserve(JsonNode body);

        RemoteResult cancel(JsonNode body);

        RemoteResult confirm(String idempotencyKey, JsonNode body);
    }

    /** contracts/http/payment/v1. */
    public interface Payment {
        RemoteResult initiate(String idempotencyKey, String correlationId, JsonNode body);

        RemoteResult cancel(String transactionId, String idempotencyKey, String correlationId, JsonNode body);

        RemoteResult refund(String transactionId, String idempotencyKey, String correlationId, JsonNode body);

        RemoteResult clientData(String transactionId, String correlationId);

        RemoteResult getPayment(String transactionId, String correlationId);

        RemoteResult getRefund(String refundTransactionId, String correlationId);
    }
}

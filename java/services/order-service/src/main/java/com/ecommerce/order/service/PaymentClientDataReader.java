package com.ecommerce.order.service;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Relays Stripe client data fresh from Payment on every read; Order never stores the
 * secret (data.md §3.5, Checkout ADR-010). Unusable data is returned with null secret.
 */
@Component
public class PaymentClientDataReader {

    private final Gateways.Payment payment;
    private final Json json;

    public PaymentClientDataReader(Gateways.Payment payment, Json json) {
        this.payment = payment;
        this.json = json;
    }

    /** Checkout PaymentClientData shape: attempt, transaction, usable, secret, key, expires_at. */
    public ObjectNode read(int attemptNumber, String transactionId, String correlationId) {
        RemoteResult result = payment.clientData(transactionId, correlationId);
        if (!result.isAccepted()) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Payment client data is temporarily unavailable; retry", List.of(), 2, Map.of());
        }
        JsonNode body = result.body();
        boolean usable = body.path("usable").asBoolean(false);
        ObjectNode data = json.object();
        data.put("payment_attempt_number", attemptNumber);
        data.put("payment_transaction_id", transactionId);
        data.put("usable", usable);
        data.put("client_secret", usable ? body.path("client_secret").asText(null) : null);
        data.put("publishable_key", usable ? body.path("publishable_key").asText(null) : null);
        JsonNode expires = body.get("client_expires_at");
        data.put("expires_at", expires == null || expires.isNull() ? null : expires.asText());
        return data;
    }
}

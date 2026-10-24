package com.ecommerce.order.client;

import com.ecommerce.order.config.OrderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP adapters for the three outbound ports. */
@Configuration
public class HttpGateways {

    @Bean
    @ConditionalOnMissingBean(Gateways.Inventory.class)
    public Gateways.Inventory inventoryGateway(OrderProperties properties, ObjectMapper mapper, MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations) {
        OrderProperties.Clients c = properties.clients();
        HttpRemote http = new HttpRemote("inventory", c.inventoryBaseUrl(), c.connectTimeout(), c.inventoryRead(), mapper,
                c.serviceName(), meters, c.maxConcurrentCalls(), observations);
        String base = "/internal/inventory/v1";
        return new Gateways.Inventory() {
            @Override
            public RemoteResult reserve(Gateways.Command command, JsonNode body) {
                return http.post(base + "/reservations", headers(command), body);
            }

            @Override
            public RemoteResult commit(String orderId, Gateways.Command command, JsonNode body) {
                return http.post(base + "/reservations/" + enc(orderId) + "/commit", headers(command), body);
            }

            @Override
            public RemoteResult release(String orderId, Gateways.Command command, JsonNode body) {
                return http.post(base + "/reservations/" + enc(orderId) + "/release", headers(command), body);
            }

            @Override
            public RemoteResult releaseCommittedBeforeDispatch(String orderId, Gateways.Command command, JsonNode body) {
                return http.post(base + "/reservations/" + enc(orderId) + "/release-committed-before-dispatch",
                        headers(command), body);
            }

            @Override
            public RemoteResult registerRefundScope(Gateways.Command command, JsonNode body) {
                return http.post(base + "/refund-scopes", headers(command), body);
            }

            @Override
            public RemoteResult closeRefundScope(String scopeId, Gateways.Command command, JsonNode body) {
                return http.post(base + "/refund-scopes/" + enc(scopeId) + "/close", headers(command), body);
            }

            @Override
            public RemoteResult closeIncompleteRefundScope(String scopeId, Gateways.Command command, JsonNode body) {
                return http.post(base + "/refund-scopes/" + enc(scopeId) + "/close-incomplete", headers(command), body);
            }

            @Override
            public RemoteResult cancelRefundScope(String scopeId, Gateways.Command command) {
                return http.post(base + "/refund-scopes/" + enc(scopeId) + "/cancel", headers(command), null);
            }

            @Override
            public RemoteResult getReservation(String orderId) {
                return http.get(base + "/reservations/" + enc(orderId), Map.of());
            }

            @Override
            public RemoteResult getRefundScope(String scopeId) {
                return http.get(base + "/refund-scopes/" + enc(scopeId), Map.of());
            }

            @Override
            public RemoteResult getCommand(String commandId) {
                return http.get(base + "/commands/" + enc(commandId), Map.of());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(Gateways.Promotion.class)
    public Gateways.Promotion promotionGateway(OrderProperties properties, ObjectMapper mapper, MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations) {
        OrderProperties.Clients c = properties.clients();
        HttpRemote http = new HttpRemote("promotion", c.promotionBaseUrl(), c.connectTimeout(), c.promotionRead(), mapper,
                c.serviceName(), meters, c.maxConcurrentCalls(), observations);
        String base = "/internal/v1/promotion-allocation";
        return new Gateways.Promotion() {
            @Override
            public RemoteResult reserve(JsonNode body) {
                return http.post(base + "/reservations", Map.of(), body);
            }

            @Override
            public RemoteResult cancel(JsonNode body) {
                return http.post(base + "/cancellations", Map.of(), body);
            }

            @Override
            public RemoteResult confirm(String idempotencyKey, JsonNode body) {
                return http.post(base + "/confirmations", Map.of("Idempotency-Key", idempotencyKey), body);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(Gateways.Payment.class)
    public Gateways.Payment paymentGateway(OrderProperties properties, ObjectMapper mapper, MeterRegistry meters,
            io.micrometer.observation.ObservationRegistry observations) {
        OrderProperties.Clients c = properties.clients();
        HttpRemote http = new HttpRemote("payment", c.paymentBaseUrl(), c.connectTimeout(), c.paymentRead(), mapper,
                c.serviceName(), meters, c.maxConcurrentCalls(), observations);
        return new Gateways.Payment() {
            @Override
            public RemoteResult initiate(String key, String correlationId, JsonNode body) {
                return http.post("/v1/payments/initiate", command(key, correlationId), body);
            }

            @Override
            public RemoteResult cancel(String txn, String key, String correlationId, JsonNode body) {
                return http.post("/v1/payments/" + enc(txn) + "/cancel", command(key, correlationId), body);
            }

            @Override
            public RemoteResult refund(String txn, String key, String correlationId, JsonNode body) {
                return http.post("/v1/payments/" + enc(txn) + "/refund", command(key, correlationId), body);
            }

            @Override
            public RemoteResult clientData(String txn, String correlationId) {
                return http.get("/v1/payments/" + enc(txn) + "/client-data", Map.of("X-Correlation-Id", correlationId));
            }

            @Override
            public RemoteResult getPayment(String txn, String correlationId) {
                return http.get("/v1/payments/" + enc(txn), Map.of("X-Correlation-Id", correlationId));
            }

            @Override
            public RemoteResult getRefund(String refundTxn, String correlationId) {
                return http.get("/v1/refunds/" + enc(refundTxn), Map.of("X-Correlation-Id", correlationId));
            }
        };
    }

    private static Map<String, String> headers(Gateways.Command command) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", command.commandId());
        headers.put("X-Correlation-ID", command.correlationId());
        headers.put("X-Causation-ID", command.causationId());
        return headers;
    }

    private static Map<String, String> command(String key, String correlationId) {
        return Map.of("Idempotency-Key", key, "X-Correlation-Id", correlationId);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

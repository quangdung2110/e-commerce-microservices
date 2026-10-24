package com.ecommerce.checkout.client;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cart checkout snapshot: {@code GET /internal/v1/carts/{user_id}/checkout-snapshot}. Identifiers
 * and quantities only; a Redis miss at Cart is an empty list, which is not an error here.
 */
@Component
public class CartClient {

    static final int MAX_LINES = 50;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public CartClient(WebClient.Builder builder, CheckoutProperties properties, ObjectMapper objectMapper,
                        ClientTransports transports) {
        this.webClient = UpstreamHttp.build(builder, transports, "cart", properties.endpoints().cartBaseUrl(),
                properties.bulkheads().cart());
        this.objectMapper = objectMapper;
        objectMapper.readerFor(Snapshot.class); // Resolve the real DTO reader before serving the first request.
    }

    public record Line(String productId, String skuId, int quantity) {
    }

    public record Snapshot(List<Line> items) {

        /** Contract checks of interfaces.md: at most 50 lines, quantity 1-100, unique SKUs. */
        Snapshot validated() {
            if (items == null || items.size() > MAX_LINES) {
                throw new ContractViolationException("cart snapshot: items missing or more than " + MAX_LINES);
            }
            Set<String> seen = new HashSet<>();
            for (Line line : items) {
                if (line == null || blank(line.productId()) || blank(line.skuId())
                        || line.productId().length() > 64 || line.skuId().length() > 64) {
                    throw new ContractViolationException("cart snapshot: invalid identifier");
                }
                if (line.quantity() < 1 || line.quantity() > 100) {
                    throw new ContractViolationException("cart snapshot: quantity out of range");
                }
                if (!seen.add(line.skuId())) {
                    throw new ContractViolationException("cart snapshot: duplicate sku_id");
                }
            }
            return this;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public Mono<Snapshot> fetch(String userId, RequestContext context) {
        return webClient.get()
                .uri("/internal/v1/carts/{userId}/checkout-snapshot", userId)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readBody(response, Snapshot.class, objectMapper).map(Snapshot::validated);
                });
    }
}

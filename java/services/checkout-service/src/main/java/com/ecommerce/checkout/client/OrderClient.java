package com.ecommerce.checkout.client;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Order checkout-order-submissions (interfaces.md, "Mapping ket qua Order"):
 * <ul>
 *   <li>{@code POST /internal/v1/checkout-order-submissions} with {@code Idempotency-Key = checkout_token}
 *       and the exact stored payload;</li>
 *   <li>{@code GET /internal/v1/checkout-order-submissions/{checkout_token}}, the only source of
 *       Payment client data on replay (ADR-010).</li>
 * </ul>
 * Order answers {@code 409}/{@code 422} with a problem for the terminal business outcomes; those are
 * returned as {@link Rejected} (business result) while {@code 400/401/403/503}/timeouts surface as
 * dependency failures for {@code DependencyGuard} to classify. A {@code 503} keeps Order's problem code.
 */
@Component
public class OrderClient {

    private static final Pattern ORDER_ID = Pattern.compile("^[1-9][0-9]*$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OrderClient(WebClient.Builder builder, CheckoutProperties properties, ObjectMapper objectMapper) {
        this.webClient = UpstreamHttp.build(builder, "order", properties.endpoints().orderBaseUrl(),
                Math.max(properties.bulkheads().orderPost(), properties.bulkheads().orderGet()));
        this.objectMapper = objectMapper;
    }

    /**
     * Payment client data relayed from Payment through Order. Checkout passes it to the buyer and never
     * stores it; {@code usable=false} always carries null secret and key.
     */
    public record Payment(int paymentAttemptNumber, String paymentTransactionId, boolean usable, String clientSecret,
                          String publishableKey, String expiresAt) {
    }

    public record Ready(String checkoutToken, String orderId, Payment payment) {
    }

    /** Order's answer to POST: success ({@code created} for 201) or a terminal business rejection. */
    public sealed interface PostResponse permits Created, Rejected {
    }

    public record Created(boolean created, Ready ready) implements PostResponse {
    }

    /** {@code 409}/{@code 422}: {@code problem} is Order's body, mapped by the caller. */
    public record Rejected(int status, JsonNode problem) implements PostResponse {

        public String code() {
            return problem.path("code").asText("");
        }
    }

    /** Recovery read. {@code failure} is Order's problem for FAILED and RECALCULATION_REQUIRED. */
    public record Status(String checkoutToken, String state, String orderId, Payment payment, JsonNode failure) {
    }

    public Mono<PostResponse> post(String token, String payload, RequestContext context) {
        return webClient.post()
                .uri("/internal/v1/checkout-order-submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .header("Idempotency-Key", token)
                .bodyValue(payload)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == HttpStatus.CREATED.value() || status == HttpStatus.OK.value()) {
                        return UpstreamHttp.readTree(response, objectMapper)
                                .map(tree -> (PostResponse) new Created(status == HttpStatus.CREATED.value(),
                                        ready(tree, token)));
                    }
                    if (status == HttpStatus.CONFLICT.value() || status == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                        return UpstreamHttp.readTree(response, objectMapper)
                                .map(tree -> (PostResponse) new Rejected(status, tree));
                    }
                    return UpstreamHttp.unexpectedStatusWithCode(response, objectMapper);
                });
    }

    /** {@code 404} means Order has no submission for the token: a result, not an error. */
    public Mono<Optional<Status>> get(String token, RequestContext context) {
        return webClient.get()
                .uri("/internal/v1/checkout-order-submissions/{token}", token)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().thenReturn(Optional.<Status>empty());
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readTree(response, objectMapper)
                            .map(tree -> Optional.of(status(tree, token)));
                });
    }

    static Ready ready(JsonNode tree, String token) {
        if (!token.equalsIgnoreCase(tree.path("checkout_token").asText(""))
                || !"PENDING_PAYMENT".equals(tree.path("order_status").asText(""))) {
            throw new ContractViolationException("order submission: token or order_status does not match");
        }
        return new Ready(tree.path("checkout_token").asText(), orderId(tree.path("order_id")),
                payment(tree.path("payment")));
    }

    static Status status(JsonNode tree, String token) {
        if (!token.equalsIgnoreCase(tree.path("checkout_token").asText(""))) {
            throw new ContractViolationException("order submission status: token does not match");
        }
        String state = tree.path("state").asText("");
        switch (state) {
            case "PAYMENT_READY" -> {
                return new Status(token, state, orderId(tree.path("order_id")), payment(tree.path("payment")), null);
            }
            case "PREPARING" -> {
                return new Status(token, state, null, null, null);
            }
            case "RECALCULATION_REQUIRED", "FAILED" -> {
                JsonNode failure = tree.path("failure");
                if (!failure.isObject()) {
                    throw new ContractViolationException("order submission status: failure missing for " + state);
                }
                return new Status(token, state, null, null, failure);
            }
            default -> throw new ContractViolationException("order submission status: unknown state");
        }
    }

    private static String orderId(JsonNode node) {
        String value = node.asText("");
        if (!node.isTextual() || value.length() > 19 || !ORDER_ID.matcher(value).matches()) {
            throw new ContractViolationException("order submission: order_id invalid");
        }
        return value;
    }

    private static Payment payment(JsonNode node) {
        if (!node.isObject() || !node.path("payment_attempt_number").isIntegralNumber()
                || node.path("payment_attempt_number").asInt() < 1 || !node.path("usable").isBoolean()
                || node.path("payment_transaction_id").asText("").isBlank()) {
            throw new ContractViolationException("order submission: payment client data invalid");
        }
        boolean usable = node.path("usable").asBoolean();
        String secret = node.path("client_secret").isTextual() ? node.path("client_secret").asText() : null;
        String key = node.path("publishable_key").isTextual() ? node.path("publishable_key").asText() : null;
        if (usable && (secret == null || key == null)) {
            throw new ContractViolationException("order submission: usable payment without client secret");
        }
        return new Payment(node.path("payment_attempt_number").asInt(), node.path("payment_transaction_id").asText(),
                usable, usable ? secret : null, usable ? key : null,
                node.path("expires_at").isTextual() ? node.path("expires_at").asText() : null);
    }
}

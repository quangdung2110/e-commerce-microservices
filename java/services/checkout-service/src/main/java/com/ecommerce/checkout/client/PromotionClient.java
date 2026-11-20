package com.ecommerce.checkout.client;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Promotion queries for Checkout. Both are pure: they never save, claim or reserve.
 * <ul>
 *   <li>{@code POST /internal/v1/promotion-discount-calculations}: the discount of zero or one
 *       voucher, only called when a {@code promotion_id} exists.</li>
 *   <li>{@code POST /internal/v1/promotion-voucher-options}: the ranked voucher tray (ADR-017).</li>
 * </ul>
 * The context is always built by Checkout from server-owned facts; {@code user_tier} is omitted
 * because there is no tier authority yet.
 */
@Component
public class PromotionClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PromotionClient(WebClient.Builder builder, CheckoutProperties properties, ObjectMapper objectMapper,
                        ClientTransports transports) {
        this.webClient = UpstreamHttp.build(builder, transports, "promotion", properties.endpoints().promotionBaseUrl(),
                Math.max(properties.bulkheads().promotionCalculation(), properties.bulkheads().promotionOptions()));
        this.objectMapper = objectMapper;
        if (properties.voucherOptionsEnabled()) {
            transports.registerReadProbe(this::warmupOptions);
        }
    }

    /** Prime the actual request encoder, outbound pool, response tree and options contract decoder. */
    private Mono<Void> warmupOptions() {
        return webClient.get().uri("/actuator/health/readiness")
                .exchangeToMono(response -> response.statusCode().value() == 200
                        ? response.releaseBody() : UpstreamHttp.unexpectedStatus(response))
                .then(Mono.defer(() -> {
                    String technicalUser = java.util.UUID.randomUUID().toString();
                    var item = new ContextItem(java.util.UUID.randomUUID().toString(), "startup", 1, 350000, List.of());
                    var context = new RequestContext(java.util.UUID.randomUUID().toString(), "", null, null, null);
                    return options(technicalUser, 350000, List.of(item), context).then();
                }));
    }

    /** One line of the trusted context: Product price and categories, Cart quantity. */
    public record ContextItem(String productId, String skuId, int quantity, long unitPrice, List<String> categoryIds) {
    }

    public record Calculation(boolean eligible, String reason, long subtotal, long discount, long finalAmount,
                              Long campaignRevision, String budgetGuard) {
    }

    public record Evaluation(String promotionId, String source, String distributionMode, boolean eligible,
                             String reason, long discount, long finalAmount, long campaignRevision) {
    }

    /** Display data of a voucher; {@code discount} stays a tree because it is a PERCENTAGE/FIXED_VND union. */
    public record Display(String code, String name, String description, JsonNode discount, long minOrderAmount,
                          String validUntil) {
    }

    public record Option(Evaluation evaluation, Display display) {
    }

    public record Options(List<Option> items, String evaluatedAt) {
    }

    public Mono<Calculation> calculate(String userId, String promotionId, long subtotal, List<ContextItem> items,
                                       RequestContext context) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("user_id", userId);
        body.put("promotion_id", promotionId);
        body.set("context", context(subtotal, items));
        return webClient.post()
                .uri("/internal/v1/promotion-discount-calculations")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readTree(response, objectMapper).map(tree -> calculation(tree, subtotal));
                });
    }

    public Mono<Options> options(String userId, long subtotal, List<ContextItem> items, RequestContext context) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("user_id", userId);
        body.set("context", context(subtotal, items));
        body.put("include_public", true);
        body.put("include_ineligible", true);
        body.put("limit", 50);
        return webClient.post()
                .uri("/internal/v1/promotion-voucher-options")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readTree(response, objectMapper).map(PromotionClient::options);
                });
    }

    private ObjectNode context(long subtotal, List<ContextItem> items) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("subtotal", money(subtotal));
        context.put("payment_method", "STRIPE");
        context.put("checkout_channel", "CHECKOUT");
        ArrayNode array = context.putArray("items");
        for (ContextItem item : items) {
            ObjectNode node = array.addObject();
            node.put("product_id", item.productId());
            node.put("sku_id", item.skuId());
            node.put("quantity", item.quantity());
            node.set("unit_price", money(item.unitPrice()));
            ArrayNode categories = node.putArray("category_ids");
            item.categoryIds().forEach(categories::add);
        }
        return context;
    }

    private ObjectNode money(long amount) {
        return objectMapper.createObjectNode().put("amount_minor", amount).put("currency", "VND");
    }

    /**
     * interfaces.md: {@code subtotal} must equal Checkout's total, {@code discount} cannot exceed it,
     * {@code final_amount} must equal the subtraction and an eligible result needs a revision above 0.
     * Anything else is a contract violation (503), never "no discount".
     */
    static Calculation calculation(JsonNode tree, long expectedSubtotal) {
        if (!tree.path("eligible").isBoolean()) {
            throw new ContractViolationException("promotion calculation: eligible missing");
        }
        boolean eligible = tree.path("eligible").asBoolean();
        long subtotal = vnd(tree.path("subtotal"));
        long discount = vnd(tree.path("discount"));
        long finalAmount = vnd(tree.path("final_amount"));
        if (subtotal != expectedSubtotal) {
            throw new ContractViolationException("promotion calculation: subtotal differs from the quotation");
        }
        if (discount > subtotal || finalAmount != subtotal - discount) {
            throw new ContractViolationException("promotion calculation: discount or final_amount inconsistent");
        }
        Long revision = tree.path("campaign_revision").isIntegralNumber()
                ? tree.path("campaign_revision").asLong() : null;
        if (eligible && (revision == null || revision < 1)) {
            throw new ContractViolationException("promotion calculation: eligible without campaign_revision");
        }
        String reason = tree.path("reason").asText("");
        if (reason.isEmpty()) {
            throw new ContractViolationException("promotion calculation: reason missing");
        }
        return new Calculation(eligible, reason, subtotal, discount, finalAmount, revision,
                tree.path("budget_guard").asText("UNKNOWN"));
    }

    static Options options(JsonNode tree) {
        JsonNode items = tree.path("items");
        if (!items.isArray() || items.size() > 50 || !tree.path("evaluated_at").isTextual()) {
            throw new ContractViolationException("voucher options: items or evaluated_at invalid");
        }
        List<Option> result = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode evaluation = item.path("evaluation");
            JsonNode display = item.path("display");
            String promotionId = evaluation.path("promotion_id").asText("");
            if (promotionId.isBlank() || promotionId.length() > 128 || !evaluation.path("eligible").isBoolean()
                    || !evaluation.path("campaign_revision").isIntegralNumber()
                    || !display.path("code").isTextual() || !display.path("name").isTextual()
                    || !display.has("discount")) {
                throw new ContractViolationException("voucher options: item invalid");
            }
            result.add(new Option(
                    new Evaluation(promotionId, evaluation.path("source").asText("UNKNOWN"),
                            evaluation.path("distribution_mode").asText(""),
                            evaluation.path("eligible").asBoolean(), evaluation.path("reason").asText("UNKNOWN"),
                            vnd(evaluation.path("discount")), vnd(evaluation.path("final_amount")),
                            evaluation.path("campaign_revision").asLong()),
                    new Display(display.path("code").asText(), display.path("name").asText(),
                            display.path("description").isTextual() ? display.path("description").asText() : null,
                            display.path("discount"), vnd(display.path("min_order_amount")),
                            display.path("valid_until").isTextual() ? display.path("valid_until").asText() : null)));
        }
        return new Options(List.copyOf(result), tree.path("evaluated_at").asText());
    }

    private static long vnd(JsonNode money) {
        if (!money.path("amount_minor").isIntegralNumber() || !"VND".equals(money.path("currency").asText())
                || money.path("amount_minor").asLong() < 0) {
            throw new ContractViolationException("promotion: money is not a non-negative VND amount");
        }
        return money.path("amount_minor").asLong();
    }
}

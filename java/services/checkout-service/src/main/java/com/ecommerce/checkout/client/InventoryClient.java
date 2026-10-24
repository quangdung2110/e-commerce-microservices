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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory allocation preview: {@code POST /internal/inventory/v1/allocation-previews}. Stateless
 * and advisory: it holds no stock and the request carries only lines, never an address. Inventory
 * requires {@code X-Correlation-ID} and {@code X-Causation-ID} on every call.
 */
@Component
public class InventoryClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public InventoryClient(WebClient.Builder builder, CheckoutProperties properties, ObjectMapper objectMapper,
                        ClientTransports transports) {
        this.webClient = UpstreamHttp.build(builder, transports, "inventory", properties.endpoints().inventoryBaseUrl(),
                properties.bulkheads().inventory());
        this.objectMapper = objectMapper;
    }

    /** A requested line; {@code lineReference} equals {@code skuId} in Checkout v1. */
    public record Line(String lineReference, String skuId, int quantity) {
    }

    public record PlannedLine(String lineReference, String skuId, String warehouseId, int quantity) {
    }

    public record RejectedLine(String skuId, int quantity) {
    }

    /** {@code ALLOCATABLE} with an exact plan, or {@code REJECT_ALL}; there is no partial outcome. */
    public sealed interface Outcome permits Allocatable, RejectAll {
    }

    public record Allocatable(String allocationPlanId, List<PlannedLine> lines) implements Outcome {
    }

    public record RejectAll(List<RejectedLine> rejectedLines) implements Outcome {
    }

    public Mono<Outcome> preview(List<Line> lines, RequestContext context) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode array = body.putArray("lines");
        lines.forEach(line -> array.addObject()
                .put("line_reference", line.lineReference())
                .put("sku_id", line.skuId())
                .put("quantity", line.quantity()));
        return webClient.post()
                .uri("/internal/inventory/v1/allocation-previews")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .header("X-Correlation-ID", context.requestId())
                .header("X-Causation-ID", context.causationId() == null ? context.requestId() : context.causationId())
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readTree(response, objectMapper).map(tree -> validated(tree, lines));
                });
    }

    /**
     * ALLOCATABLE is accepted only when every line_reference appears exactly once with the same
     * sku_id and quantity and a warehouse; anything else is a contract violation (503), never a
     * degraded plan (interfaces.md, "Mapping sang Inventory").
     */
    static Outcome validated(JsonNode tree, List<Line> requested) {
        Map<String, Line> byReference = new HashMap<>();
        requested.forEach(line -> byReference.put(line.lineReference(), line));
        String outcome = tree.path("outcome").asText("");
        switch (outcome) {
            case "ALLOCATABLE" -> {
                String planId = tree.path("allocation_plan_id").asText("");
                JsonNode lines = tree.path("lines");
                if (planId.isBlank() || planId.length() > 128 || !lines.isArray() || lines.size() != requested.size()) {
                    throw new ContractViolationException("allocation preview: plan id or lines invalid");
                }
                Set<String> seen = new HashSet<>();
                List<PlannedLine> planned = new ArrayList<>();
                for (JsonNode node : lines) {
                    String reference = node.path("line_reference").asText("");
                    Line expected = byReference.get(reference);
                    if (expected == null || !seen.add(reference)
                            || !expected.skuId().equals(node.path("sku_id").asText(""))
                            || !node.path("quantity").canConvertToInt()
                            || expected.quantity() != node.path("quantity").asInt()) {
                        throw new ContractViolationException("allocation preview: plan does not match the request");
                    }
                    String warehouse = node.path("warehouse_id").asText("");
                    if (warehouse.isBlank() || warehouse.length() > 128) {
                        throw new ContractViolationException("allocation preview: warehouse_id missing");
                    }
                    planned.add(new PlannedLine(reference, expected.skuId(), warehouse, expected.quantity()));
                }
                return new Allocatable(planId, List.copyOf(planned));
            }
            case "REJECT_ALL" -> {
                JsonNode rejected = tree.path("rejected_lines");
                if (!rejected.isArray() || rejected.isEmpty()) {
                    throw new ContractViolationException("allocation preview: REJECT_ALL without rejected_lines");
                }
                Set<String> seen = new HashSet<>();
                List<RejectedLine> lines = new ArrayList<>();
                for (JsonNode node : rejected) {
                    String reference = node.path("line_reference").asText("");
                    Line expected = byReference.get(reference);
                    if (expected == null || !seen.add(reference)
                            || !expected.skuId().equals(node.path("sku_id").asText(""))
                            || !node.path("quantity").canConvertToInt()
                            || expected.quantity() != node.path("quantity").asInt()) {
                        throw new ContractViolationException("allocation preview: rejected line does not match");
                    }
                    lines.add(new RejectedLine(expected.skuId(), expected.quantity()));
                }
                return new RejectAll(List.copyOf(lines));
            }
            default -> throw new ContractViolationException("allocation preview: unknown outcome");
        }
    }
}

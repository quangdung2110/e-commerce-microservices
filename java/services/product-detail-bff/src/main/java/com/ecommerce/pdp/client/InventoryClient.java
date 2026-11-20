package com.ecommerce.pdp.client;

import com.ecommerce.pdp.config.PdpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Availability Read API của Inventory: một batch cho mọi SKU active của PDP, không bao giờ
 * gọi theo từng SKU (PDP-BR-005). {@code Retry-After} trong {@code 503} không làm BFF chờ.
 */
@Component
public class InventoryClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public InventoryClient(WebClient.Builder builder, PdpProperties properties, ObjectMapper objectMapper,
                        ClientTransports transports) {
        this.webClient = UpstreamHttp.build(builder, transports, "inventory", properties.inventory());
        this.objectMapper = objectMapper;
        objectMapper.readerFor(InventoryPayload.class);
    }

    /** @param skuIds 1..500 SKU unique, theo đúng contract Inventory */
    public Mono<InventoryPayload> batchGet(List<String> skuIds, RequestContext context) {
        return webClient.post()
                .uri("/internal/inventory/v1/sku-availabilities:batch-get")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", context.requestId())
                .header("X-Correlation-ID", context.requestId())
                .bodyValue(Map.of("sku_ids", skuIds))
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? UpstreamHttp.readBody(response, InventoryPayload.class, objectMapper)
                                .map(InventoryPayload::validated)
                        : UpstreamHttp.unexpectedStatus(response));
    }
}

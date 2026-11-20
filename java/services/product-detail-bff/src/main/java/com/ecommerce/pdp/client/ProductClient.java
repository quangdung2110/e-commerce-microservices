package com.ecommerce.pdp.client;

import com.ecommerce.pdp.config.PdpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Buyer-read của Product: {@code GET /internal/v1/product-details/{slug}}. {@code 404} là
 * kết quả hợp lệ (slug không tồn tại, Product {@code DRAFT}/{@code HIDDEN} hoặc hết SKU
 * {@code ACTIVE}), trả về {@link Optional#empty()} và không tính là lỗi circuit.
 */
@Component
public class ProductClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ProductClient(WebClient.Builder builder, PdpProperties properties, ObjectMapper objectMapper,
                        ClientTransports transports) {
        this.webClient = UpstreamHttp.build(builder, transports, "product", properties.product());
        this.objectMapper = objectMapper;
        objectMapper.readerFor(ProductPayload.class);
    }

    public Mono<Optional<ProductPayload>> fetch(String slug, RequestContext context) {
        return webClient.get()
                .uri("/internal/v1/product-details/{slug}", slug)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", context.requestId())
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().thenReturn(Optional.<ProductPayload>empty());
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readBody(response, ProductPayload.class, objectMapper)
                            .map(payload -> Optional.of(payload.validated()));
                });
    }
}

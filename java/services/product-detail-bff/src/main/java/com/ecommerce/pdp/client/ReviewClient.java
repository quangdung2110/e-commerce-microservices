package com.ecommerce.pdp.client;

import com.ecommerce.pdp.config.PdpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * PDP summary của Review. Ngoài mesh, Review còn giữ static bearer allowlist
 * ({@code REVIEW_INTERNAL_PDP_TOKENS}) làm defense-in-depth, nên mọi request mang
 * {@code Authorization: Bearer} từ {@code PDP_REVIEW_SERVICE_TOKEN} (ADR-007).
 */
@Component
public class ReviewClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String bearer;
    private final int previewLimit;

    public ReviewClient(WebClient.Builder builder, PdpProperties properties, ObjectMapper objectMapper) {
        this.webClient = UpstreamHttp.build(builder, "review", properties.review().asUpstream());
        this.objectMapper = objectMapper;
        this.bearer = "Bearer " + properties.review().serviceToken();
        this.previewLimit = properties.review().previewLimit();
    }

    public Mono<ReviewPayload> pdpSummary(String productId, RequestContext context) {
        return webClient.get()
                .uri(uri -> uri.path("/internal/v1/reviews/products/{productId}/pdp-summary")
                        .queryParam("preview_limit", previewLimit)
                        .build(productId))
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .header("X-Request-Id", context.requestId())
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? UpstreamHttp.readBody(response, ReviewPayload.class, objectMapper)
                                .map(ReviewPayload::validated)
                        : UpstreamHttp.unexpectedStatus(response));
    }
}

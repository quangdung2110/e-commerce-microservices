package com.ecommerce.pdp.api;

import com.ecommerce.pdp.client.RequestContext;
import com.ecommerce.pdp.compose.PdpResult;
import com.ecommerce.pdp.compose.ProductDetailComposer;
import com.ecommerce.pdp.config.PdpProperties;
import com.ecommerce.pdp.resilience.Deadline;
import com.ecommerce.pdp.resilience.DependencyGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * {@code GET /api/v1/storefront/products/{slug}} — endpoint duy nhất của BFF
 * (contracts/http/product-detail-bff/v1/openapi.yaml). Mọi thao tác khác trên PDP đi thẳng
 * qua Gateway tới service sở hữu.
 */
@RestController
public class ProductDetailController {

    /** Trùng PRODUCT-BR-013 của Product: slug sai thì 404 mà không gọi Product. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final int SLUG_MAX_LENGTH = 180;

    private final ProductDetailComposer composer;
    private final PdpProperties properties;

    public ProductDetailController(ProductDetailComposer composer, PdpProperties properties) {
        this.composer = composer;
        this.properties = properties;
    }

    @GetMapping(path = "/api/v1/storefront/products/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> getProductDetail(@PathVariable("slug") String slug,
                                                         ServerHttpRequest request) {
        Deadline deadline = Deadline.startingNow(properties.deadline());
        String instance = request.getPath().value();
        return Mono.deferContextual(reactorContext -> {
            HttpHeaders headers = request.getHeaders();
            String traceId = RequestIds.traceId(reactorContext, headers);
            RequestContext context = new RequestContext(RequestIds.requestId(headers, traceId), traceId, deadline);
            if (slug.length() > SLUG_MAX_LENGTH || !SLUG.matcher(slug).matches()) {
                return Mono.just(Problems.notFound(instance));
            }
            return composer.compose(slug, context)
                    .map(result -> toResponse(result, context, instance))
                    .contextWrite(logContext -> logContext
                            .put(DependencyGuard.REQUEST_ID_KEY, context.requestId())
                            .put(DependencyGuard.TRACE_ID_KEY, context.traceId()));
        });
    }

    /**
     * Mọi đường dẫn khác dưới {@code /api/v1/storefront/products/} không phải một slug hợp
     * lệ (rỗng, dấu {@code /} cuối, nhiều segment): Gateway chuyển cả prefix tới BFF, nên
     * đây là slug sai pattern và phải trả cùng {@code 404} problem {@code no-store}.
     */
    @GetMapping(path = "/api/v1/storefront/products/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> unknownPath(ServerHttpRequest request) {
        return Problems.notFound(request.getPath().value());
    }

    private ResponseEntity<Object> toResponse(PdpResult result, RequestContext context, String instance) {
        return switch (result) {
            case PdpResult.Found found -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, found.body().degraded()
                            ? "no-store"
                            : properties.fullResponseCacheControl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(found.body());
            case PdpResult.NotFound notFound -> Problems.notFound(instance);
            case PdpResult.Unavailable unavailable ->
                    Problems.unavailable(instance, context.traceId(), unavailable.retryAfterSeconds());
        };
    }
}

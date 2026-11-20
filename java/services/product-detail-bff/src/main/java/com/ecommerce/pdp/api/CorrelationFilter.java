package com.ecommerce.pdp.api;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/** Correlation and privacy-safe access logs for success, rejected and unmatched requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class CorrelationFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        return Mono.deferContextual(context -> {
            String traceId = RequestIds.traceId(context, exchange.getRequest().getHeaders());
            String requestId = RequestIds.requestId(exchange.getRequest().getHeaders(), traceId);
            // The controller must reuse the same normalized value, including when there was no header.
            ServerWebExchange scoped = exchange.mutate().request(request -> request.headers(
                    headers -> headers.set("X-Request-Id", requestId))).build();
            scoped.getResponse().getHeaders().set("X-Request-Id", requestId);
            return chain.filter(scoped).doFinally(signal -> {
                PathPattern route = scoped.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                var status = scoped.getResponse().getStatusCode();
                log.info("request completed", StructuredArguments.kv("request_id", requestId),
                        StructuredArguments.kv("trace_id", traceId),
                        StructuredArguments.kv("method", scoped.getRequest().getMethod().name()),
                        StructuredArguments.kv("operation", route == null ? "unmatched" : route.getPatternString()),
                        StructuredArguments.kv("status", status == null ? "cancelled" : status.value()),
                        StructuredArguments.kv("duration_ms", (System.nanoTime() - started) / 1_000_000));
            });
        });
    }
}

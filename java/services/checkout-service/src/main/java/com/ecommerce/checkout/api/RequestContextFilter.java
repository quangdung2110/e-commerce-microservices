package com.ecommerce.checkout.api;

import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.Deadline;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Per-request plumbing for {@code /api/**}: starts the route's deadline the moment the request
 * arrives, normalizes the request ID, and publishes a {@link RequestContext} for the controller and
 * the error handler. It also marks every response, errors included, {@code Cache-Control: private,
 * no-store} (interfaces.md) and writes one access line with the route template, never the path:
 * {@code {checkout_token}} is redacted (operations.md, "Alert").
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestContextFilter implements WebFilter {

    public static final String CONTEXT_ATTRIBUTE = "checkout.request-context";
    public static final String PROBLEM_CODE_ATTRIBUTE = "checkout.problem-code";

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    private final CheckoutProperties properties;

    public RequestContextFilter(CheckoutProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = routedPath(exchange);
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        Deadline deadline = Deadline.startingNow(budget(path));
        long startedAt = System.nanoTime();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "private, no-store");
            return Mono.empty();
        });
        return Mono.deferContextual(reactorContext -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String traceId = RequestIds.traceId(reactorContext, headers);
            exchange.getResponse().getHeaders().set("X-Request-Id", RequestIds.requestId(headers, traceId));
            RequestContext requestContext = new RequestContext(
                    RequestIds.requestId(headers, traceId), traceId,
                    RequestIds.traceparent(reactorContext, headers), null, deadline);
            exchange.getAttributes().put(CONTEXT_ATTRIBUTE, requestContext);
            return chain.filter(exchange).contextWrite(context -> context
                    .put(com.ecommerce.checkout.resilience.DependencyGuard.REQUEST_ID_KEY, requestContext.requestId())
                    .put(com.ecommerce.checkout.resilience.DependencyGuard.TRACE_ID_KEY, traceId));
        }).doFinally(signal -> accessLog(exchange, startedAt));
    }

    /**
     * The path WebFlux routes on: each segment decoded and without {@code ;param}. The raw path would
     * leave {@code /%61pi/v1/checkout/preview} outside the {@code /api/} branch, so the controller would
     * run without a request context, and {@code /api/v1/checkout/%70review} on the Submit budget.
     */
    private static String routedPath(ServerWebExchange exchange) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element : exchange.getRequest().getPath().pathWithinApplication().elements()) {
            path.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }

    /** Deadlines of ADR-014: Preview 5 s, voucher options 1.5 s, Submit and status 20 s. */
    private Duration budget(String path) {
        if (path.endsWith("/voucher-options")) {
            return properties.deadlines().voucherOptions();
        }
        if (path.equals("/api/v1/checkout/preview")) {
            return properties.deadlines().preview();
        }
        return properties.deadlines().submit();
    }

    private void accessLog(ServerWebExchange exchange, long startedAt) {
        RequestContext context = exchange.getAttribute(CONTEXT_ATTRIBUTE);
        PathPattern route = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object problemCode = exchange.getAttribute(PROBLEM_CODE_ATTRIBUTE);
        var status = exchange.getResponse().getStatusCode();
        log.info("request completed",
                StructuredArguments.kv("method", exchange.getRequest().getMethod().name()),
                StructuredArguments.kv("route", route == null ? "unmatched" : route.getPatternString()),
                StructuredArguments.kv("status", status == null ? "cancelled" : status.value()),
                StructuredArguments.kv("problem_code", problemCode == null ? "" : problemCode),
                StructuredArguments.kv("duration_ms", Duration.ofNanos(System.nanoTime() - startedAt).toMillis()),
                StructuredArguments.kv("request_id", context == null ? "" : context.requestId()),
                StructuredArguments.kv("trace_id", context == null ? "" : context.traceId()));
    }
}

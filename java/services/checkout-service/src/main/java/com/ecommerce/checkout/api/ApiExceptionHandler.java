package com.ecommerce.checkout.api;

import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.observability.CheckoutMetrics;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.store.RedisUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.Map;

/**
 * Every failure becomes a {@code CheckoutProblem} (ADR-015). The contract has no 500: an exception that
 * is not a business problem means Checkout could not do its job, which is {@code 503 CHECKOUT_UNAVAILABLE},
 * never a stack trace or a downstream body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemRenderer renderer;
    private final CheckoutMetrics metrics;

    public ApiExceptionHandler(ProblemRenderer renderer, CheckoutMetrics metrics) {
        this.renderer = renderer;
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> problem(ApiException error, ServerWebExchange exchange) {
        return respond(error.problem(), exchange);
    }

    @ExceptionHandler(RedisUnavailableException.class)
    public ResponseEntity<Map<String, Object>> redis(RedisUnavailableException error, ServerWebExchange exchange) {
        log.error("Redis failed; Checkout cannot work without its workflow state", error);
        return respond(Problem.of(ProblemCode.CHECKOUT_UNAVAILABLE,
                "A required service is temporarily unavailable, try again shortly"), exchange);
    }

    @ExceptionHandler({ServerWebInputException.class, DecodingException.class, DataBufferLimitException.class})
    public ResponseEntity<Map<String, Object>> badInput(Exception error, ServerWebExchange exchange) {
        return respond(Problem.of(ProblemCode.INVALID_REQUEST, "The request is not valid"), exchange);
    }

    /** Unknown route or method: keep the HTTP status, speak problem+json. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException error, ServerWebExchange exchange) {
        int status = error.getStatusCode().value();
        if (status >= 500) {
            return unexpected(error, exchange);
        }
        return respond(Problem.of(ProblemCode.INVALID_REQUEST, "The request is not valid").withStatus(status),
                exchange);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> unexpected(Throwable error, ServerWebExchange exchange) {
        log.error("unexpected failure while handling a Checkout request", error);
        return respond(Problem.of(ProblemCode.CHECKOUT_UNAVAILABLE,
                "A required service is temporarily unavailable, try again shortly"), exchange);
    }

    private ResponseEntity<Map<String, Object>> respond(Problem problem, ServerWebExchange exchange) {
        RequestContext context = exchange.getAttribute(RequestContextFilter.CONTEXT_ATTRIBUTE);
        exchange.getAttributes().put(RequestContextFilter.PROBLEM_CODE_ATTRIBUTE, problem.code().name());
        metrics.problem(operation(exchange.getRequest().getPath().value()), problem.code().name());
        String traceId = context == null ? "0".repeat(32) : context.traceId();
        return renderer.render(problem, exchange.getRequest().getPath().value(), traceId);
    }

    /** Bounded label for {@code checkout_problems_total}. */
    private static String operation(String path) {
        if (path.equals("/api/v1/checkout/preview")) {
            return "preview";
        }
        if (path.endsWith("/voucher-options")) {
            return "voucher_options";
        }
        if (path.equals("/api/v1/checkout/submit")) {
            return "submit";
        }
        if (path.startsWith("/api/v1/checkout/submissions/")) {
            return "status";
        }
        return "other";
    }
}

package com.ecommerce.checkout.api;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.problem.Problem;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link Problem} as {@code application/problem+json} with the {@code CheckoutProblem}
 * fields (ADR-015): {@code type, title, status, detail, instance, code, retryable, action, trace_id}
 * followed by the code-specific extensions. A downstream body is never copied into the response.
 */
@Component
public class ProblemRenderer {

    private static final String TYPE_BASE = "https://errors.ecommerce.local/";

    private final CheckoutProperties properties;

    public ProblemRenderer(CheckoutProperties properties) {
        this.properties = properties;
    }

    public ResponseEntity<Map<String, Object>> render(Problem problem, String instance, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + problem.code().slug());
        body.put("title", problem.code().title());
        body.put("status", problem.status());
        body.put("detail", problem.detail());
        body.put("instance", instance);
        body.put("code", problem.code().name());
        body.put("retryable", problem.code().retryable());
        body.put("action", problem.code().action().name());
        body.put("trace_id", traceId);
        body.putAll(problem.extensions());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (problem.status() == 503) {
            int seconds = problem.retryAfterSeconds() != null
                    ? problem.retryAfterSeconds()
                    : properties.retryAfter().unavailableSeconds();
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, Math.min(60, seconds))));
        }
        return builder.body(body);
    }
}

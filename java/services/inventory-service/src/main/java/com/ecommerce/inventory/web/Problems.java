package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.common.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds RFC 9457 problem bodies with the project extensions every Inventory surface
 * requires: stable {@code code}, {@code retryable}, {@code correlation_id} and, when
 * supplied, {@code command_id} (interfaces.md §5).
 */
@Component
public class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final ObjectMapper objectMapper;

    public Problems(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode body(ErrorCode code, String detail, List<ApiException.FieldError> errors,
                           String commandId) {
        RequestContext context = RequestContext.current();
        ObjectNode problem = objectMapper.createObjectNode();
        problem.put("type", code.typeUri());
        problem.put("title", code.title());
        problem.put("status", code.status());
        problem.put("detail", truncate(detail == null || detail.isBlank() ? code.title() : detail));
        problem.put("instance", context == null || context.path() == null ? "/" : context.path());
        problem.put("code", code.name());
        problem.put("retryable", code.retryable());
        problem.put("correlation_id", correlationId(context));
        if (commandId != null && commandId.length() >= 16 && commandId.length() <= 128) {
            problem.put("command_id", commandId);
        }
        if (errors != null && !errors.isEmpty()) {
            ArrayNode array = problem.putArray("errors");
            errors.stream().limit(100).forEach(error -> array.addObject()
                    .put("field", truncate(error.field(), 200))
                    .put("message", truncate(error.message(), 500)));
        }
        return problem;
    }

    public ResponseEntity<Object> response(ErrorCode code, String detail, List<ApiException.FieldError> errors,
                                           String commandId, Integer retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status()).contentType(PROBLEM_JSON);
        Integer wait = retryAfterSeconds != null ? retryAfterSeconds : defaultRetryAfter(code);
        if (wait != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
        }
        return builder.body(body(code, detail, errors, commandId));
    }

    /**
     * contracts ADR-005: every {@code 429} and every retryable {@code 503} carries {@code Retry-After}, also when the code
     * that raised it had no estimate. A {@code 503} that is not retryable never does.
     */
    static Integer defaultRetryAfter(ErrorCode code) {
        if (code.status() == 429) {
            return 1;
        }
        return code.status() == 503 && code.retryable() ? 2 : null;
    }

    private static String correlationId(RequestContext context) {
        if (context != null && context.hasValidCorrelationId()) {
            return context.correlationId();
        }
        return "inv-" + Ids.newId();
    }

    private static String truncate(String value) {
        return truncate(value, 1000);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

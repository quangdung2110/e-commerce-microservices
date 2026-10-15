package com.ecommerce.order.web;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** RFC 9457 problem bodies with the shared project extensions (code, retryable). */
@Component
public class Problems {

    private final ObjectMapper mapper;

    public Problems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode body(ErrorCode code, String detail, List<ApiException.FieldError> errors, String instance,
                           Map<String, Object> extensions) {
        ObjectNode problem = mapper.createObjectNode();
        problem.put("type", code.typeUri());
        problem.put("title", code.title());
        problem.put("status", code.status());
        String text = detail == null || detail.isBlank() ? code.title() : detail;
        problem.put("detail", text.length() > 1000 ? text.substring(0, 1000) : text);
        problem.put("instance", instance == null ? "/" : instance);
        problem.put("code", code.name());
        problem.put("retryable", code.retryable());
        if (errors != null && !errors.isEmpty()) {
            ArrayNode array = problem.putArray("errors");
            errors.stream().limit(100).forEach(e -> array.addObject()
                    .put("field", truncate(e.field(), 200)).put("message", truncate(e.message(), 500)));
        }
        if (extensions != null) {
            extensions.forEach((key, value) -> problem.set(key, mapper.valueToTree(value)));
        }
        return problem;
    }

    public ResponseEntity<Object> response(ApiException e, HttpServletRequest request) {
        return response(e.code(), e.getMessage(), e.errors(), request == null ? null : request.getRequestURI(),
                e.retryAfterSeconds(), e.extensions());
    }

    public ResponseEntity<Object> response(ErrorCode code, String detail, List<ApiException.FieldError> errors,
                                           String instance, Integer retryAfter, Map<String, Object> extensions) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Integer wait = retryAfter != null ? retryAfter : defaultRetryAfter(code);
        if (wait != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
        }
        return builder.body(body(code, detail, errors, instance, extensions));
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}

package com.ecommerce.pdp.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code 404}/{@code 503} dạng {@code application/problem+json} theo HTTP convention.
 * Cả hai luôn {@code no-store}: {@code 404} để CDN không giữ "không tìm thấy" sau khi
 * Product được activate, {@code 503} để không kéo dài sự cố (ADR-004 §5, ADR-007).
 */
final class Problems {

    private static final String TYPE_BASE = "https://contracts.ecommerce.local/problems/product-detail/";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {
    }

    static ResponseEntity<Object> notFound(String instance) {
        Map<String, Object> body = body("PRODUCT_DETAIL_NOT_FOUND", "Product detail not found",
                HttpStatus.NOT_FOUND, "No published product exists at this address", instance, false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .contentType(PROBLEM_JSON)
                .body(body);
    }

    /**
     * Wait advertised when the 503 is not caused by an open circuit: a timeout, a refused connection or a full bulkhead has no
     * estimate of its own, so the default of contracts ADR-005 applies (PDP ADR-010).
     */
    static final int DEFAULT_RETRY_AFTER_SECONDS = 2;

    /** @param retryAfterSeconds thời gian mở còn lại khi circuit breaker của Product đang mở; null thì dùng mặc định */
    static ResponseEntity<Object> unavailable(String instance, String traceId, Integer retryAfterSeconds) {
        Map<String, Object> body = body("PRODUCT_DETAIL_UNAVAILABLE", "Product detail temporarily unavailable",
                HttpStatus.SERVICE_UNAVAILABLE, "The product catalog could not be read, try again shortly",
                instance, true);
        body.put("trace_id", traceId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .contentType(PROBLEM_JSON);
        builder.header(HttpHeaders.RETRY_AFTER,
                String.valueOf(retryAfterSeconds != null ? retryAfterSeconds : DEFAULT_RETRY_AFTER_SECONDS));
        return builder.body(body);
    }

    private static Map<String, Object> body(String code, String title, HttpStatus status,
                                            String detail, String instance, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + code.toLowerCase().replace('_', '-'));
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", instance);
        body.put("code", code);
        body.put("retryable", retryable);
        return body;
    }
}

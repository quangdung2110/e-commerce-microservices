package com.ecommerce.pdp.client;

import com.ecommerce.pdp.resilience.Deadline;

/**
 * Dữ liệu của một request PDP mà mọi lời gọi dependency cần.
 *
 * @param requestId request ID đã chuẩn hóa (ADR-007): gửi qua {@code X-Request-Id} tới
 *                  Product/Review và {@code X-Correlation-ID} tới Inventory
 * @param traceId   trace ID 32 hex của request, dùng cho {@code trace_id} của problem
 */
public record RequestContext(String requestId, String traceId, Deadline deadline) {
}

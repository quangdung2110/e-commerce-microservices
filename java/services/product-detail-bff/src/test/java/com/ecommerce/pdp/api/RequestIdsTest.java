package com.ecommerce.pdp.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdsTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    @DisplayName("X-Request-Id hợp lệ được giữ nguyên")
    void validRequestIdIsKept() {
        assertThat(RequestIds.requestId(headers("X-Request-Id", "req:2026.09-25_abc"), TRACE_ID))
                .isEqualTo("req:2026.09-25_abc");
    }

    @Test
    @DisplayName("X-Request-Id thiếu, quá ngắn, quá dài hoặc có ký tự lạ bị thay bằng trace ID")
    void malformedRequestIdFallsBackToTraceId() {
        assertThat(RequestIds.requestId(new HttpHeaders(), TRACE_ID)).isEqualTo(TRACE_ID);
        for (String bad : new String[] {"abc", "a".repeat(129), "has space in it", "tab\tinside1", "xss<script>"}) {
            assertThat(RequestIds.requestId(headers("X-Request-Id", bad), TRACE_ID)).as(bad).isEqualTo(TRACE_ID);
        }
    }

    @Test
    @DisplayName("Không có tracing thì trace ID lấy từ traceparent, sai định dạng thì sinh 32 hex ngẫu nhiên")
    void traceIdFallbacks() {
        assertThat(RequestIds.traceId(Context.empty(),
                headers("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))).isEqualTo(TRACE_ID);
        String zeroTrace = "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01";
        assertThat(RequestIds.traceId(Context.empty(), headers("traceparent", zeroTrace)))
                .matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(RequestIds.traceId(Context.empty(), headers("traceparent", "garbage")))
                .matches("[0-9a-f]{32}");
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }
}
